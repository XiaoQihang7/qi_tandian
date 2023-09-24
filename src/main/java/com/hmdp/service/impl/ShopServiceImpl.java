package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        /*
         * 在本机上店铺信息直接从数据库获取需要的响应时间为117ms
         * 而使用redis之后只需要24ms左右
         * */

        //缓存穿透
//        return queryWithPassThrough(id);

        //缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /*
     * 缓存击穿（逻辑过期解决方案）
     * */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);//10个线程

    private Shop queryWithLogicalExpire(Long id) {
        String shopkey = CACHE_SHOP_KEY + id;
        //1、从redis查询数据,存的是商铺对象，String和Hash都行
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);

        //2、缓存中不存在，直接返回null，因为第一次查询店铺时会将店铺信息加入redis（设置了逻辑过期时间不会过期）
        if (StrUtil.isBlank(shopJson)) {//null或""
            //3、存在，直接返回
            return null;
        }

        //4、命中，需要将json数据反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1、未过期，直接返回店铺信息
            return shop;
        }

        //5.2、已过期，需要缓存重建,使用锁机制防止多线程进入
        //6、缓存重建
        //6.1、获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //6.2、判断是否获取锁成功
        boolean b = tryLock(lockKey);
        if (b) {
            //成功，再次检测redis缓存是否存在，DoubleCheck
            String doubleCheck = stringRedisTemplate.opsForValue().get(shopkey);
            if (doubleCheck != null) {
                RedisData data = JSONUtil.toBean(doubleCheck, RedisData.class);
                return JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
            }
            //6.3、doubleCheck没有数据，开启独立线程，实现缓存重（使用线程池，不要自己创建：性能不高需创建又销毁）)
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //缓存重建
                try {
                    this.saveShop2Redis(id, 20L); //封装的api用于设置逻辑过期时间
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
            //6.4、失败，返回过期时间的商铺信息
            return shop;
    }

    /*
    * 缓存击穿（互斥锁解决方案）
    * */
    private Shop queryWithMutex(Long id) {
        String shopkey = CACHE_SHOP_KEY + id;
        //1、从redis查询数据,存的是商铺对象，String和Hash都行
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);

        //2、存在，直接返回
        //isNotBlank()刚好可以将【防止缓存穿透的空字符串】 ！=null且长度不为0
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //【缓存穿透】多判断一重：是否不为null，不为null则【只能为”“】，不让再查数据库
        if (shopJson !=null) {
            //返回错误信息
            return null;
        }

        //【避免缓存击穿】实现缓存重建
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop= null; //try-catch公用shop对象
        try {
            boolean b = tryLock(lockKey);
            //判断是否获取成功
            if (!b) {
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功，再次检测redis缓存是否存在，DoubleCheck
            String doubleCheck = stringRedisTemplate.opsForValue().get(shopkey);
            if (doubleCheck != null) {
                RedisData data = JSONUtil.toBean(doubleCheck, RedisData.class);
                return JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
            }

            //3、缓存中不存在，且获取锁成功，从数据库中根据id查询数据
            shop = getById(id);
            //由于数据库在本地，访问速度快，为了更容易发送并发冲突，休眠一下
            Thread.sleep(200);

            //4、不存在，返回
            if (shop==null){
                //【防止缓存穿透】、将空值写入redis
                stringRedisTemplate.opsForValue().set(shopkey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //5、存在，写入redis
            //【保证缓存和数据库(修改)信息同步】、设置有效时间
            stringRedisTemplate.opsForValue().set(shopkey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES); //value需要字符串类型
        } catch (InterruptedException e) {
            throw new RuntimeException(e);//交给全局异常处理
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    /*
    * 获取锁
    * */
    private boolean tryLock(String key){
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ifAbsent);//防止Boolean类型自动拆箱，布尔值为空，产生空指针异常
    }
    /*
    * 释放锁
    * */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /*
    * 封一个设置逻辑过期时间的api
    * */
    private void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds( expireSeconds) );
        //3、写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /*
    * 缓存穿透
    * 保存数据时，可改为Shop类型数据，返回对象为空或数据本身
    * */
    private Result queryWithPassThrough(Long id) {
        String shopkey = CACHE_SHOP_KEY + id;
        //1、从redis查询数据,存的是商铺对象，String和Hash都行
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);

        //2、存在，直接返回
        //isNotBlanlk()刚好可以将【防止缓存穿透的空字符串】
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //【缓存穿透】多判断一重：是否不为null，不为null则只能为”“，不让再查数据库
        if (shopJson !=null) {
            //返回错误信息
            return Result.fail("店铺不存在");
        }

        //3、不存在，从数据库中查询数据
        Shop shop=getById(id);
        //4、不存在，返回
        if (shop==null){
            //【防止缓存击穿】、将空值写入redis
            stringRedisTemplate.opsForValue().set(shopkey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return Result.fail("店铺不存在");
        }
        //5、存在，写入redis
        //【保证缓存和数据库(修改)信息同步】、设置有效时间
        stringRedisTemplate.opsForValue().set(shopkey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES); //value需要字符串类型
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    /*
    * 根据geo位置信息返回店铺信息
    * */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1、判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 无坐标信息，不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {    //list查询的是end页数（也就是当前页*页大小）；而from是（当前页-1）*页大小
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

/*
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, it->getById(it) , CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }*/
}
