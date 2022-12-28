package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlogs(Integer current);

    Result queryBlogById(String id);

    void makeLike(Long id, Long userId);

    Result getLikeUserList(String id);

    Long saveBlog(Blog blog);

    ScrollResult scrollFollowBlog(Long lastId, Integer offset);

    List<Blog> queryBlogByIds(List<Long> blogIdList);

}
