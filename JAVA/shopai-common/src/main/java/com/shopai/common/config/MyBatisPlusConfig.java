package com.shopai.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatisPlus配置类，用于添加分页插件与乐观锁插件。
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.MyBatisPlusConfig 迁移至公共库。
 * 使用 MyBatis 的服务通过组件扫描自动装配本配置。
 * <p>
 * 分层改造修复（2026-10-05）：原先本类手工定义了 SqlSessionFactory / SqlSessionTemplate 两个 Bean，
 * 会导致 MyBatis-Plus 自动配置失效 —— 表现为 PaginationInnerInterceptor（分页插件）虽被创建却
 * 从未挂载到 SqlSessionFactory，导致 IService.page() 分页不生效。现移除这两个手工 Bean，
 * 仅保留 mybatisPlusInterceptor()，交回 MP 自动配置装配：自动配置会把该拦截器应用到它自己创建的
 * SqlSessionFactory，并按各服务 application.yml 的 mybatis-plus.mapper-locations /
 * type-aliases-package 生效，一次修复分页问题。
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)   // 只有类路径存在时才生效
public class MyBatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器链：分页 + 乐观锁。
     * <p>
     * <b>乐观锁插件为什么必须注册（踩过的坑）</b>：
     * 实体上只要标了 {@code @Version}，MyBatis-Plus 生成的 UPDATE 语句就会自动加上
     * {@code AND version = ?}，而那个占位符的参数名是内部常量
     * {@code MP_OPTLOCK_VERSION_ORIGINAL} —— <b>它由 OptimisticLockerInnerInterceptor 在执行前填充</b>。
     * 如果没注册该插件，占位符就没人赋值，运行时会抛：
     * <pre>
     *   BindingException: Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found.
     *   Available parameters are [param1, et]
     * </pre>
     * 表现为「所有带 @Version 的实体，其 updateById 全部失败」。
     * <p>
     * 注意：这个错误<b>不是</b>「乐观锁不生效（静默不加 version 条件）」，
     * 而是直接报错 —— 因为 MP 在生成 SQL 阶段就认了 @Version 注解，
     * 只是执行阶段没有拦截器来填参数。<b>所以只要用了 @Version 就一定要注册本插件。</b>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加分页插件，指定数据库类型
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 添加乐观锁插件（@Version 注解依赖它来填充 MP_OPTLOCK_VERSION_ORIGINAL 参数）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
