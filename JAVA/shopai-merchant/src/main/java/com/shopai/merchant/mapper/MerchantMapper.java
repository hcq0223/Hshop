package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopai.merchant.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商户数据访问层
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.MerchantMapper 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.mapper，XML namespace 同步更新。
 * 原顾客端 LIKE 搜索方法 selectMerchantSearchPage 已迁移至 shopai-search（ES 搜索），
 * 此处不再保留（设计文档 5.5：/api/user/customer/searchMerchants → /api/search/merchant）。
 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    /**
     * 分页查询商户列表（附带商品数量统计）
     * @param page 分页参数对象
     * @param keyword 店铺名称搜索关键字（可选）
     * @return 分页的商户列表
     */
    IPage<Merchant> selectMerchantPage(
            Page<Merchant> page,
            @Param("keyword") String keyword
    );

    /**
     * 根据用户ID查询单个商户详情
     * @param userId
     * @return
     */
    Merchant selectByUserId(@Param("userId") Long userId);

    /**
     * 根据商户ID查询商户公开信息（客户端使用）
     * 联查 users 表确保用户状态正常且未删除
     */
    Merchant selectPublicInfoById(@Param("merchantId") Long merchantId);

    /**
     * 查询所有已认证且评分大于等于指定值的商户，按评分降序
     * （可用于首页推荐优质商户）
     */
    List<Merchant> selectVerifiedWithMinRating(@Param("minRating") BigDecimal minRating);

    /**
     * 根据 userId 联查商户信息 + 关联的 User 基础信息
     * @param userId
     * @return
     */
    Merchant selectDetailByUserId(@Param("userId") Long userId);

    /**
     * 原子按增量变更商户资金（替代「selectById + updateById」读改写）
     * <p>
     * <b>为什么必须改成单条原子 UPDATE</b>：
     * <ol>
     *   <li><b>并发下会丢更新</b>：读改写是「读 55 → 内存加 45 → 写 100」。
     *       两个请求同时读到 55，各自加 45，最后都写 100 —— 实际应为 145，
     *       商户凭空少了 45 元。改成 {@code SET x = x + delta} 由数据库在行锁内累加，
     *       天然不会丢。</li>
     *   <li><b>持锁时间更长</b>：读改写要先 SELECT 拿到实体、在 Java 里算、
     *       再发 UPDATE；两条 SQL 之间还夹着 MyBatis-Plus 的实体装配。
     *       单条 UPDATE 把「读 + 算 + 写」压缩成一次行锁持有，
     *       大幅缩短锁窗口，减少其它事务等锁超时的概率。</li>
     * </ol>
     * <p>
     * <b>COALESCE 的作用</b>：资金字段历史上可能是 NULL（早期数据），
     * {@code NULL + 1} 在 SQL 里结果是 NULL，会把余额直接写成 NULL。
     * 用 {@code COALESCE(x, 0)} 兜住，与原 Java 侧 {@code nvl(...)} 语义一致。
     * <p>
     * 增量传 null 表示「本次不变动该字段」，用 {@code COALESCE(#{delta}, 0)} 实现：
     * 传 null 时加 0，等价于不变动，从而一条 SQL 同时支持「只改部分字段」。
     *
     * @param merchantId     商户ID
     * @param revenueDelta   累计营收增量（可为 null / 负数）
     * @param availableDelta 可用余额增量（可为 null / 负数）
     * @param frozenDelta    冻结余额增量（可为 null / 负数）
     * @return 影响行数；0 表示商户不存在
     */
    int changeFundsAtomic(@Param("merchantId") Long merchantId,
                          @Param("revenueDelta") BigDecimal revenueDelta,
                          @Param("availableDelta") BigDecimal availableDelta,
                          @Param("frozenDelta") BigDecimal frozenDelta);
}
