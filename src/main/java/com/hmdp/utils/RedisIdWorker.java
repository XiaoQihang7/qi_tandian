package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1675209600;//2023.2.1的时间戳
    private static final String BEGIN_KEY = "icr:";
    private static final int COUNT_BITS  = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        //1、生成时间戳(31位)
        LocalDateTime now = LocalDateTime.now();
        //Java9后新方法，将时间转换为自1970-01-01T00：00：00Z纪元以来的秒数（纪元秒）
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2、生成序列号(32位)
        //2.1、获取当天日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy_MM_dd"));
        //redis自增长是有限制的2^64-1 ,且我们使用的序列号为32位，32位还是容易超出的，所以不用单个数自增，使用日期！每日数不同
        long count = stringRedisTemplate.opsForValue().increment(BEGIN_KEY + keyPrefix + ":" + date); //count值为返回的新增数

        //3、拼接返回
        return timestamp << COUNT_BITS | count;
    }
 }
