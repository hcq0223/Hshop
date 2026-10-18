package com.shopai.user.config;

import com.shopai.user.entity.User;
import com.shopai.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 系统初始化器
 * 项目启动时检查并创建默认管理员账号
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.DataInitializer 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.config（仅用户服务负责初始化管理员账号）。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        initAdminUser();
    }

    /**
     * 初始化管理员账号
     * 如果不存在则创建，如果已存在则跳过（不覆盖已有密码）
     */
    private void initAdminUser() {
        String adminUsername = "Hcqhcq";
        User existing = userMapper.selectByUsername(adminUsername);
        if (existing != null) {
            log.info("管理员账号 [{}] 已存在，跳过初始化", adminUsername);
            return;
        }

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode("hcq159357"));
        admin.setPhone("15381282857");
        admin.setEmail("2490187773@qq.com");
        admin.setRole("admin");
        admin.setStatus(1);

        userMapper.insert(admin);
        log.info("========================================");
        log.info("  管理员账号已初始化");
        log.info("  用户名: Hcqhcq");
        log.info("  密码:   hcq159357");
        log.info("  手机号: 15381282857");
        log.info("  邮箱:   2490187773@qq.com");
        log.info("========================================");
    }
}
