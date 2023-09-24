package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    RedisIdWorker redisIdWorker;
    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private RedissonClient redissonClient;

    @Test
    void testRedisson() throws InterruptedException {
        //获取锁（可重入），指定锁的名称
//        RLock lock = redissonClient.getLock("anyLock");
        //尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位,不設置參數表示失敗不重試
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
//        boolean Lock = lock.tryLock(1, TimeUnit.SECONDS);
//        lock.tryLock();
        //判断释放获取成功
        if (isLock) {
            try {
                System.out.println("执行业务");
            } finally {
                //释放锁
                lock.unlock();
            }
        }
    }


    @Test
    public void RedisIdTest() {
        LocalDateTime of = LocalDateTime.of(2023, 2, 1, 0, 0, 0);
        long second = of.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);


        CountDownLatch latch = new CountDownLatch(300); //这里是用于异步下计时

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("id");
                System.out.println(id);
            }
            latch.countDown(); //线程全部结束再完成后续操作
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        long end = System.currentTimeMillis();
        System.out.println(end - begin);
    }

    @Resource
    RedissonClient redissonClient2;

    @Resource
    RedissonClient redissonClient3;

    private RLock lock;

    /**
     * springboot版本的不同会导致junit版本的不同 在测试接口或者业务层方法的时候 会使用到Before注解
     * junit4.x版本之前使用的是@Before注解
     * junit5.x版本以后使用的是@BeforeEach注解
     * 两个注解功能相同 表示在测试类中任何一个测试方法执行之前都先执行该注解标注的方法
     */
    @BeforeEach
    void setUp() {
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");

        //创建联锁multiLock
        this.lock = redissonClient.getMultiLock(lock1, lock2, lock3); //getMultiLock和new一样。

    }



    @Resource
    IShopService shopService;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Test
    public void testLoadDateShop() {
        //1、查询所有店铺信息
        List<Shop> shops = shopService.list();
        //2、根据店铺类型分组存入redis
        Map<Long, List<Shop>> collect = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3、根据店铺类型为key值，同类型店铺为value存入redis
        for (Map.Entry<Long,List<Shop>> entry : collect.entrySet()){
            //3.1、获取店铺类型键值
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺的集合,并写入
            //使用GeoLocation数据类型（String，Point）进行写入
            List<RedisGeoCommands.GeoLocation<String>> locations =new ArrayList<>(shops.size());
            for (Shop shop : entry.getValue()) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations); //批量写入redis


        }
    }


}
