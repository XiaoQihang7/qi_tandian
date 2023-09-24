package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.jdbc.Null;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 [session]/redis
        if (StrUtil.isNotEmpty(code) && code.length() == 6) {
            session.setAttribute("code", code);
            //a4.保存验证码到redis,2分钟过期
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        } else {
            return Result.fail("验证码发送异常");
        }
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        // TODO 实现登录功能
        //1.从前端传来的数据中获取手机号校验
        String phone = loginForm.getPhone();
        //2、校验手机号是否还正常
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式异常");
        }
        //3、校验验证码是否正确
        //3,1、从session中获取数据
//        String code = (String) session.getAttribute("code");

        //a3、从redis中获取数据
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String formCode = loginForm.getCode();
        if (formCode.equals(code)) {
            // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
            User user = userService.query().eq("phone", phone).one();
            //5、查看用户是否存在
            if (user == null) {
                // 6.不存在，创建新用户并保存
                user = userService.createUserWithPhone(phone);
            }
            //7、将用户转为适合的对象格式传出（防止信息泄露）
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

           /* //将数据存入session中
            session.setAttribute("user",userDTO);*/

            //将数据存入redis中
            //7.1生成随机token，true无下划线
            String token = UUID.randomUUID().toString(true);
            //7.2将用户对象转为map集合,[stringRedisTemplate要求<string,string>类型进行添加]
            Map<String, Object> beanToMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((key, value) -> value.toString())
            );
            //7.3将用户信息存入token键值
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, beanToMap);

            //7.4设置key的有效期
            stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);//25day


            /*
             * 使用Session登入不需要返回登入凭证，
             * 因为每一个session都有唯一的一个id，访问tomcat时，这个id就自动的写入了这个cookie，
             * 以后请求就会带着这个JSessionId找到session会话，获取存在的值
             * */
            return Result.ok(token);
        }

        return Result.fail("验证码输入错误");
    }

    /**
     * 登出功能
     *
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        // TODO 实现登出功能
        String token = request.getHeader("authorization");
        stringRedisTemplate.opsForHash().delete(LOGIN_USER_KEY + token,"nickName","id","icon");//删除的字段不可为空
        return Result.ok();
    }


    /*
     * 【登入校验】获取Session/redis中的值
     *  如：在session存值的情况，Request Headers中cookie存在的JSessionID即为登入凭证
     * 判断是否存在用户登入并返回
     * 【存在问题】
     * 如果以后业务越来越多，难道在每个业务内都进行登入校验的逻辑吗
     * （解决）：在每个业务开始前就统一进行登入校验
     * */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    // UserController 根据id查询用户(笔者)
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

}
