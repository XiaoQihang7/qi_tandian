package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.object.UpdatableSqlQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 航神
 * @since 2022-12-22
 */
@Service
//@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT; //静态常量需要赋初值，或使用代码块在类加载时声明

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));//设置脚本位置
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    // TODO PostConstruct作用:在方法上加该注解会在项目启动的时候执行该方法，也可以理解为在spring容器初始化的时候执行该方法。
    //  @PostConstruct注解的方法将会在依赖注入完成后被自动调用
    @PostConstruct
    private void init() {
     /*   //3.获取全局代理对象 ,代理对象的初始化放在这里是不行的，why？类的初始化先于spring容器完成吗？
        proxy= (IVoucherOrderService) AopContext.currentProxy();*/
//        IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
        SECKILL_ORDER_EXECUTOR.submit(new Runnable() {
            @Override
            public void run() {
                while (true) {//如果这里为flag=false，就直接提交完成了
                    try {
                        //试试
                        if(proxy==null){
                            continue;
                        }
                        //1、获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                        //TODO 为什么这里的消费者name要设置为c1，我们好像并没有设置消费者呀？-这只是一个消费者的名字 xreadgroup和xread有什么区别
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));//从未读订单读起：>
                        //2、判断消息获取是否成功
                        if (null == list || list.isEmpty()) {
                            //2.1、如果获取失败，说明没有消息，继续下一次循环
                            continue;
                        }
                        //2.2、解析消息中的订单信息
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> value = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                        //2.2、如果获取成功，可以下单
                        handleVoucherOrder(voucherOrder);

                        //3、ACK确认 SACK stream.orders g1 消息id
                        stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());

                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                        //处理消息的过程中抛出异常
                        handlePendingList();
                    }
                }
            }

            private void handlePendingList() {
                while (true) {
                    try {
                        //1、获取Pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create("stream.orders", ReadOffset.from("0")));//从第0个订单信息读起,0
                        //2、判断消息获取是否成功
                        if (null == list || list.isEmpty()) {
                            //2.1、如果获取失败，说明pending-list没有异常消息，结束循环
                            break;
                        }
                        //2.2、解析消息中的订单信息
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> value = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                        //2.2、如果获取成功，可以下单
                        handleVoucherOrder(voucherOrder);

                        //3、ACK确认 SACK stream.orders g1 消息id
                        stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());

                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                        //抛出异常，不需要调自己方法实现递归了，在while循环内。

                        //不想让抛出异常太过频繁
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        });

    }

    /**
     * 秒杀方案优化1：阻塞队列方式实现
     */
/*    public class VoucherOrderHandlerZ implements Runnable {
        @Override
        public void run() {
            while (true){
                try {
                    //从阻塞队列中取数据
                    VoucherOrder voucherOrder = blockingQueue.take();
                    //创建订单的
                    handleVoucherOrder(voucherOrder);  //异步线程不需要返回给前端数据
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/

    //使用多线程去执行阻塞队列中的数据
    //异步处理线程池
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //2.1、创建阻塞队列（当一个线程尝试从队列中获取元素时，队列中不存在元素就会被阻塞，有元素才会被唤醒）
    private final BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);

    //3.获取全局代理对象
    public IVoucherOrderService proxy;

    //尝试解决集群下抢购异常的问题
    public boolean flag = false;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、获取用户id
        Long userId = UserHolder.getUser().getId();
        //1.1、生成全局唯一订单Id
        long orderId = redisIdWorker.nextId("order");
        //1.2、执行lua脚本,【redis处理参数均为字符串类型】

        //2.获取全局代理对象
        proxy= (IVoucherOrderService) AopContext.currentProxy();

        flag=true;

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        //TODO 断言是什么时候提出的？ jdk1.4
        assert result != null; //如果为false，则程序抛出AssertionError，并终止执行
        int r = result.intValue();
        // 3.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
/*
        //TODO 有购买资格，保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);

        //2.2、加入阻塞队列
        blockingQueue.add(voucherOrder);
*/
        //TODO 有购买资格，保存只在redis消息队列


/*        //3、获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();*/

        // 3.返回订单ID
        return Result.ok(orderId);

    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //处理从阻塞中获取的值
        //1、获取用户id
        Long userId = voucherOrder.getUserId();
        //2、使用Redisson获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean islock = lock.tryLock();
        //3、判断锁是否获取成功
        if (!islock) {
            //获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
// spring 的事务是通过LocalThread来保证线程安全的，事务和当前线程绑定， 搞了多个线程自然会让事务失效。
                //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy(); //这样不行，事务失效
//            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.1.【查询订单,以用户id和优惠劵id同时存在判断用户是否购买过】
//        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId,voucherOrder.getVoucherId());
//        count(queryWrapper);
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //未开始
            return Result.fail("秒杀尚未开始");
        }
        //3、判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //结束
            return Result.fail("秒杀已经结束");
        }
        //4、判断库存是否充足
        if (voucher.getStock()<1){
            //库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

    *//*    //获取【分布式锁】对象，设置每一个用户对应的独有key值
        SimpleRedisLock simpleRedislock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取分布式锁
        boolean isLock = simpleRedislock.tryLock(1200);*//*

        //使用Redission框架
        RLock simpleRedislock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = simpleRedislock.tryLock();
        //判断是否成功，【一般推荐使用反向逻辑，防止逻辑嵌套，出现错误】
        if (!isLock){
            //获取锁失败，返回错误或重试；这里是一人一单的业务不应该重试
            //返回错误
            return Result.fail("已抢购一次");
        }

        //【悲观锁】
//        synchronized (userId1.toString().intern()) { //防止同一用户每次请求的string对象不同造成异常
        //获取代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId); //在接口中创建createVoucherOrder即可
        } finally {
            simpleRedislock.unlock();
        }
//        }

    }*/

    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId1) {
    /*
    【一人一单】
    * */
        //根据用户id和该用用户的此类秒杀劵是否为0判断是否抢购
        Integer count = query().eq("user_id", userId1).eq("voucher_id", voucherId).count();
        if (count > 0) {
            //用户已经抢过了
            return Result.fail("用户已经抢购一次");
        }

        //5、扣减库存
        seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId)
                //使用乐观锁解决超卖问题，“库存为版本号”，将库存变动判断改为大于0解决成功率低的问题
                .gt("stock", 0).update();
        //6、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1、订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2、用户id
        Long userId = UserHolder.getUser().getId(); //其实用户id已经传过来了，无需再去获取
        voucherOrder.setUserId(userId);
        //6.3、代金卷id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7、返回订单id
        return Result.ok(orderId);

    }
}
