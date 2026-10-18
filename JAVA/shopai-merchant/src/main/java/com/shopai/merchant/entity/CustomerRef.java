package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 客户引用实体（商家服务内的只读精简版）
 * <p>
 * 微服务拆分说明：customers 表归属 shopai-user 服务。评价列表/详情联查（ReviewMapper.xml
 * 的 JOIN customers 填充评价人昵称/头像）。当前「共享单库」阶段可直查；后续拆独立库需改为
 * Feign 调用用户服务补字段。
 */
@Data
@TableName("customers")
public class CustomerRef {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户昵称 */
    private String nickname;

    /** 客户头像 */
    private String avatarUrl;

    /** 占位字段（兼容原实体挂载的 user 对象，商家服务暂不使用） */
    @TableField(exist = false)
    private Object user;
}
