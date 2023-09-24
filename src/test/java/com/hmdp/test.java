package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;

@SpringBootTest
@Slf4j
public class test {

    @Test
    public void test(){
        ArrayList<String> strings = new ArrayList<>();
        strings.add("1");
        strings.add("2");
        strings.add("3");
        log.info("集合的长度为：{}",strings.size());
        Object[] objects = strings.toArray();
        log.info("数组的长度为：{}",objects.length);
        String str ="asdadw";
        log.info("字符串长度为：{}",str.length());
    }
}
