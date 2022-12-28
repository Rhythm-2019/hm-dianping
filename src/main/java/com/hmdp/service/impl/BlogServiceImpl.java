package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlogs(Integer current) {

        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::fillUserInfoToBlog);

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(String id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("没有找到文章");
        }
        fillUserInfoToBlog(blog);
        return Result.ok(blog);
    }

    @Override
    public void makeLike(Long id, Long userId) {

        if (hadLike(id, userId)) {
            // 取消点赞
            boolean isSuccess = this.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // TODO 需要考虑数据的有效期及如何同步到数据库
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        } else {
            // 点赞
            boolean isSuccess = this.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        }

    }

    @Override
    public Result getLikeUserList(String id) {
        Set<String> userIdSet = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (userIdSet == null) {
            return Result.ok();
        }
        return Result.ok(userService.queryByIds(CollectionUtil.map(userIdSet, Long::valueOf, true)));
    }

    @Override
    public Long saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        this.save(blog);
        // 推送到粉丝收信箱
        followService.query().eq("follow_user_id", blog.getUserId()).list()
                 .forEach(follow -> {
                     stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + follow.getUserId(),
                             blog.getId().toString(), System.currentTimeMillis());
                 });

        // 返回id
        return blog.getId();
    }

    @Override
    public List<Blog> queryBlogByIds(List<Long> blogIdList) {
        return this.query().in("id", blogIdList)
                .last(String.format("ORDER BY FIELD(id, %s)", StrUtil.join(",", blogIdList)))
                .list()
                .stream()
                .peek(this::fillUserInfoToBlog)
                .collect(Collectors.toList());
    }

    @Override
    public ScrollResult scrollFollowBlog(Long lastId, Integer offset) {
        if (UserHolder.getUser() == null) {
            return new ScrollResult().setMinTime(lastId).setOffset(offset);
        }
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + UserHolder.getUser().getId(), 0, lastId, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return new ScrollResult().setMinTime(lastId).setOffset(offset);
        }

        int nextOffset = 1;
        double nextLastId = lastId * 1.0;
        List<Long> blogIdList = new ArrayList<>(typedTuples.size());

        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            double score = typedTuple.getScore();
            if (score == nextLastId) {
                nextOffset++;
            }
            if (score < nextLastId) {
                nextLastId = score;
                nextOffset = 1;
            }
            blogIdList.add(Long.valueOf(typedTuple.getValue()));
        }
        return new ScrollResult()
                .setMinTime((long) nextLastId)
                .setOffset(nextOffset)
                .setList(this.queryBlogByIds(blogIdList));
    }

    private boolean hadLike(Long blogId, Long userId) {
        return stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blogId, userId.toString()) != null;
    }

    private void fillUserInfoToBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        UserDTO userDTO = UserHolder.getUser();
        if (userDTO != null) {
            blog.setIsLike(this.hadLike(blog.getId(), userDTO.getId()));
        }
    }
}
