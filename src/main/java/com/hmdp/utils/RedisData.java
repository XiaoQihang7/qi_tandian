package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime; //逻辑过期时间
    private Object data; //万能数据类型，加入这个属性就不需要再继承shop对象了
}
