package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 航神
 * @since 2022-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /*
    * 根据笔记id查询对应笔记
    * */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (null==blog){
            return Result.fail("笔记为空");
        }
        // 查询用户,添加用户信息（包含用户是否点赞）
        getUserInfo(blog);
//        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /*
    * 分页查询笔记展示在首页
    * */
    @Override
    public Result queryBlogHot(Integer current) {
        // 根据用户查询，以点赞数倒叙排列
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户,添加用户信息
        records.forEach(this::getUserInfo);
        return Result.ok(records);
    }

    /*
    * 点赞逻辑，根据笔记id更新点赞信息
    * */
    @Override
    public Result likeBlog(Long id) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1、在redis加入笔记的set集合，用来存储每一篇笔记点赞的用户id
        //score用来判断zset中key是否存在该成员
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        if (null==score){
            //2.1、点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                //2.2.数据库更新成功后，可以点赞，存入redis
//                stringRedisTemplate.opsForSet().add(BLOG_LIKED_KEY + id, userId.toString());
                //可以使用时间戳作为分数，这样即是有序的也是递增的
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY+id,userId.toString(),System.currentTimeMillis());
            }

        }else {
            //3、如果点赞了取消点赞-从redis删除
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (isSuccess) {
//                stringRedisTemplate.opsForSet().remove(BLOG_LIKED_KEY + id, userId.toString());
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY+id,userId.toString());
            }
        }
        return Result.ok();
    }

    //只要有用到查询blog的地方都会调用此方法进行判断是否点赞
    private void getUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //判断用户是否点赞并高亮显示
        //这段代码可以复用，单独封装为一个方法
        isBlogLiked(blog);
    }

    private void isBlogLiked(Blog blog) {
        //判断用户是否登入
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        //判断用户是否点赞
        Long id = blog.getId();
//        Boolean member = stringRedisTemplate.opsForSet().isMember(BLOG_LIKED_KEY + id, userId.toString());
        //使用zset集合
        Long userId = user.getId();
        Double member = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        //已点赞
//        blog.setIsLike(Boolean.TRUE.equals(member));
        blog.setIsLike(member!=null);
    }

    /*
    * 获取点赞列表功能
    * */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY+id;
        //1、查询前top5点赞用户zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4); //显示最近点赞的五个人
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        ////遍历集合使用","按顺序拼接
        String idStr = StrUtil.join(",", ids);
        //3、根据用户id查询用户WHERE id IN (5,1) ORDER BY FIELD("id",5,1)
        List<UserDTO> userDTOStream = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4、返回DTO
        return Result.ok(userDTOStream);
    }

    /*
    * 重构添加博客功能，加入zset集合
    * */
    @Override
    public Result addBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    /*
    * 初次传入参数：当前时间戳分数max，从max分数后第几条开始查询offset
    * */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count 从大到小分页查询
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);//五个参数1.key值、2.min、3.max与redis中顺序不同
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 假设时间戳为：5 4 4 2 2
            // 5 != 0 --> minTime=5; nextOffset = 1;
            // 4 != 5 --> minTime=4; nextOffset = 1;
            // 4 == 4 --> minTime=4; nextOffset = 2;
            // 2 != 4 --> minTime=2; nextOffset = 1;
            // 2 == 2 --> minTime=2; nextOffset = 2; 最小的时间戳有两个，偏移量需要额外+1

            // 4.1.获取id，存入list
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));//防止空指针异常
            // 4.2.获取分数(时间戳）
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time; //覆盖找到最小时间
                os = 1;
            }
        }
        //除了计算重复的分数外，还需要加上上一次偏移量定位到本次查询的起始位置 ×
//        os = minTime == max ? os : os + offset;
        // 5.根据id查询blog；不可以使用listById去查询，因为这是基于IN的查询，出现倒叙，要加入FIELD指定顺序
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //笔记是可以点赞的，还要查询blog的笔者相关信息
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户信息
            getUserInfo(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
}
