package com.hmdp.common;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * 登入校验拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {

    //由于这个类是自定义的，非spring注解构建，所以不能使用spring依赖注入，可以使用构造器注入，也可以加上注解@component等
    StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
/*        //1、从session中获取用户信息
        UserDTO user = (UserDTO) request.getSession().getAttribute("user"); //子可转父,后取消了继承关系
        UserDTO userDTO = (UserDTO) user;

    */

        /*
        * 只需要判断是否需要拦截
        * */
        if (UserHolder.getUser()==null){
            //用户为空，需要拦截
            response.setStatus(401);
            return false;
        }



 /*       //a1、从redis中获取用户信息
        //a1.1从请求头获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){//判空   ”"!=null
            //token不存在，拦截
            response.setStatus(401);
            return false;
        }
        //entries获取所有hash对象，为空返回一个空map
        Map<Object, Object> userDTO = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);

        //2、判断该信息是否存在
        if(userDTO.isEmpty()) {
            //3、不存在：拦截
            response.setStatus(401);
            return false;
        }
        //将查询到的map对象转为userDto对象
        UserDTO dtoClass = BeanUtil.fillBeanWithMap(userDTO,new UserDTO(), false);

        //4、存在，存入ThreadLocal中
        UserHolder.saveUser(dtoClass);
*/
        //5、放行
        return HandlerInterceptor.super.preHandle(request, response, handler); //true
    }

    /*渲染之后返回给用户之前*/
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        //业务执行完毕，移除用户（避免内存泄漏）
        UserHolder.removeUser();


        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
