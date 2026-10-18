package com.shopai.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.PageRequest;
import com.shopai.common.PageResult;
import com.shopai.common.Result;
import com.shopai.common.constant.IndexTypes;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.user.dto.UserDto;
import com.shopai.user.dto.UserIndexView;
import com.shopai.user.entity.User;
import com.shopai.user.feign.SearchIndexClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台用户列表查询服务（ES 版，替代原 Redis 列表缓存 + 跨库 JOIN）
 * <p>
 * 微服务拆分说明（列表查询改造）：
 * 改造前后台用户列表的实现同时有两个问题：
 * <ol>
 *   <li><b>缓存失效过粗</b>：{@code cache:user:list:{hash}} 按 (关键字, 角色, 状态, 页码)
 *       哈希，而任意用户变更都 {@code deleteByPrefix(CACHE_USER_LIST)}
 *       把**所有筛选组合、所有页码**的缓存清空；</li>
 *   <li><b>跨库联查</b>：SQL 里 {@code users LEFT JOIN customers LEFT JOIN merchants}
 *       才能支持「按客户昵称 / 店铺名搜索用户」，这在「独立库」语义下不允许。</li>
 * </ol>
 * 改造后两个问题一起解决：列表查 shopai-search 的 user 索引，
 * 索引里已冗余 {@code customerNickname / shopName} 两个检索字段
 * （由用户服务在回源组装时补齐，见 {@code UserIndexAssembler}），
 * 因此既不需要跨库 JOIN，也不需要缓存这一层。
 * <p>
 * 路由不变：本类只被用户服务内部调用，AdminUserController 的对外路径与响应结构保持不变。
 * <p>
 * 降级策略：搜索服务不可用时返回空页并记录错误日志，避免搜索服务故障放大成用户服务不可用。
 */
@Service
public class UserListIndexService {

    private static final Logger log = LoggerFactory.getLogger(UserListIndexService.class);

    @Autowired
    private SearchIndexClient searchIndexClient;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 后台用户分页列表
     * <p>
     * 对应原实现 {@code userMapper.selectByConditions(role, status, keyword, ...)}，
     * 关键字原本匹配 username/phone/email + 客户昵称 + 店铺名，
     * 现在由 ES 的 multi_match 一次覆盖这五个字段。
     *
     * @param page    分页请求（含 page/size/keyword）
     * @param role    角色过滤（可选）
     * @param status  状态过滤（可选）
     * @return 分页结果（UserDto，保持对外类型不变）
     */
    public PageResult<UserDto> listUsers(PageRequest page, String role, Integer status) {
        IndexListQuery query = new IndexListQuery();
        query.setIndexType(IndexTypes.USER);
        query.setPage(page.getPage());
        query.setSize(page.getSize());
        query.setKeyword(page.getKeyword());
        query.setSortField("id");
        query.setSortOrder("desc");

        Map<String, String> filters = new HashMap<>();
        if (role != null && !role.isBlank()) {
            filters.put("role", role);
        }
        if (status != null) {
            filters.put("status", String.valueOf(status));
        }
        query.setFilters(filters);

        IndexListResult result = queryIndex(query);
        List<UserDto> dtos = toUserDtos(result);
        return new PageResult<>(result.getTotal(), page.getPage(), page.getSize(), dtos);
    }

    /**
     * 统计用户总数（供仪表盘/列表页脚使用）
     * <p>
     * 说明：这里只取 total，不取记录（size=1 以减少传输），
     * 避免为了一个计数把整页数据拉回来。
     */
    public long countUsers(String role, Integer status) {
        IndexListQuery query = new IndexListQuery();
        query.setIndexType(IndexTypes.USER);
        query.setPage(1);
        query.setSize(1);
        Map<String, String> filters = new HashMap<>();
        if (role != null && !role.isBlank()) {
            filters.put("role", role);
        }
        if (status != null) {
            filters.put("status", String.valueOf(status));
        }
        query.setFilters(filters);
        return queryIndex(query).getTotal();
    }

    private IndexListResult queryIndex(IndexListQuery query) {
        try {
            Result<IndexListResult> r = searchIndexClient.list(query);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
            log.warn("用户列表索引查询返回异常: code={}, msg={}",
                    r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用搜索服务查询用户列表失败, filters={}, page={}",
                    query.getFilters(), query.getPage(), e);
        }
        IndexListResult empty = new IndexListResult();
        empty.setRecords(Collections.emptyList());
        empty.setTotal(0);
        empty.setPage(query.safePage());
        empty.setSize(query.safeSize());
        empty.setPages(0);
        return empty;
    }

    /**
     * 索引记录 → UserDto
     * <p>
     * 单条转换失败跳过该条，不因一条脏数据导致整页失败。
     */
    private List<UserDto> toUserDtos(IndexListResult result) {
        if (result == null || result.getRecords() == null) {
            return Collections.emptyList();
        }
        List<UserDto> list = new ArrayList<>(result.getRecords().size());
        for (Object record : result.getRecords()) {
            UserIndexView v;
            if (record instanceof UserIndexView view) {
                v = view;
            } else {
                try {
                    v = objectMapper.convertValue(record, UserIndexView.class);
                } catch (Exception e) {
                    log.error("用户索引记录转换失败，已跳过该条: {}", record, e);
                    continue;
                }
            }
            UserDto dto = new UserDto();
            // 复用 BeanUtils 的字段同名拷贝，与改造前的 User→UserDto 拷贝保持一致
            User user = new User();
            user.setId(parseLong(v.getId()));
            user.setUsername(v.getUsername());
            user.setPhone(v.getPhone());
            user.setEmail(v.getEmail());
            user.setRole(v.getRole());
            user.setStatus(v.getStatus());
            user.setCreateTime(v.getCreateTime());
            user.setUpdateTime(v.getUpdateTime());
            BeanUtils.copyProperties(user, dto);
            list.add(dto);
        }
        return list;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
