package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopTypeDTO;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.*;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    /*
     * 从数据库加载商铺类型使用了79ms
     * */
    @Override
    public Result queryAll() {

        /*返回值类型
        * [{"id":1,"name":"美食","icon":"/types/ms.png","sort":1},...]
        * */
        //1、从数据库获取商品类型是否存在 【//直接将这个map的值传入前端，会出现转义字符,建议直接传送对象！！！】
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(CACHE_TYPE_KEY);

/*        Iterator<Map.Entry<Object, Object>> iterator = entries.entrySet().iterator();
        for (Map.Entry<?, ?> objectObjectEntry : entries.entrySet()) {
            Map.Entry<?,?> ne= (Map.Entry<?, ?>) objectObjectEntry.getKey();
        }*/


        //2、存在，返回
        if (MapUtil.isNotEmpty(entries)) {
            List<Object> typeList = new ArrayList<>();
            /*
             * 多个数据转换不了,不可foreach直接进行遍历map
             * map也不可以直接获得迭代器
             * */

            for (Object typeKey:entries.keySet()) {
                String o = (String) entries.get(typeKey);
                ShopTypeDTO shopTypeDTO = JSONUtil.toBean(o, ShopTypeDTO.class);

                /*一个额map集合怎么直接转bean？*/
//            ShopType dtoClass = BeanUtil.fillBeanWithMap(entries,new ShopType(), false);
            typeList.add(shopTypeDTO);
            }
//            String jsonStr = JSONUtil.toJsonStr(entries.values());
//            return Result.ok(jsonStr);
            return Result.ok(typeList);
        }
        //3、不存在，从数据库查询
        QueryWrapper<ShopType> shopTypeQueryWrapper = new QueryWrapper<>();
        shopTypeQueryWrapper.orderByAsc("sort");
        List<ShopType> list = list(shopTypeQueryWrapper);

        //4、数据库不存在，返回
        if (list.isEmpty()) {
            return Result.fail("商品类型不见了");
        }

        //将list转为Map类型
//        Map<? extends Class<? extends ShopType>, ShopType> typeMap = list.stream()
//                .collect(Collectors.toMap(ShopType::getClass, Function.identity(), (key1, key2) -> key1));


        HashMap<String, String> typeMap = new HashMap<>();
        for (ShopType shopType : list) {

//            String jsonStr = JSONUtil.toJsonStr(shopType);
            ShopTypeDTO shopTypeDTO = BeanUtil.copyProperties(shopType, ShopTypeDTO.class);
            typeMap.put(shopType.getName(), JSONUtil.toJsonStr(shopTypeDTO));

          /*  Map<String, Object> beanToMap = BeanUtil.beanToMap(shopType, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((key, value) -> value.toString()));*/
            //5、存在，写入redis
            stringRedisTemplate.opsForHash()
                    .putAll(CACHE_TYPE_KEY, typeMap);
        }


        //5、返回
        return Result.ok(list);
    }
}

