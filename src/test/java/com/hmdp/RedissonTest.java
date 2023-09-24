package com.hmdp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class RedissonTest {
    @Resource
    RedissonClient redissonClient;
    @Resource
    RedissonClient redissonClient2;
    @Resource
    RedissonClient redissonClient3;

    private RLock lock;

    @BeforeEach
    void setUp(){
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");

        //创建联锁multiLock
        this.lock =redissonClient.getMultiLock(lock1,lock2,lock3); //getMultiLock和new一样。
    }

    @Test
    void testRedisson() throws InterruptedException {

        //尝试获取锁，参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位,不設置參數表示失敗不重試
//        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        boolean isLock = lock.tryLock(1, TimeUnit.SECONDS);
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


}
