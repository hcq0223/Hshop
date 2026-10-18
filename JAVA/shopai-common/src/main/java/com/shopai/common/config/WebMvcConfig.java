package com.shopai.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：CORS + 静态资源映射
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.WebMvcConfig 迁移至公共库。
 * 注意：CORS 在网关统一处理后，各业务服务的 CORS 配置可保留（双保险）或精简。
 * 静态资源 /uploads/** 由承载图片的服务或网关托管，见网关路由设计。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // 加默认值，避免未配置 upload.path 的服务启动失败
    @Value("${upload.path:D:/ShopAi/VorI/Images}")
    private String uploadPath;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ⚠️ 末尾这个 "/" 是必须的，缺了会导致所有 /uploads/** 请求 404。
        //
        // 原因：Spring 解析静态资源时走 PathResourceResolver → UrlResource.createRelative()，
        // 而 UrlResource 是按「URL 语义」把最后一段当作【文件】来替换的，
        // 它并不知道 "Images" 其实是个目录：
        //
        //   file:/D:/ShopAi/VorI/Images  +  28e983ac-....png
        //     → file:/D:/ShopAi/VorI/28e983ac-....png   ← 目录名 Images 被当成文件名替换掉了
        //
        // 补上尾斜杠后才会被识别为目录，从而正确拼成 .../Images/28e983ac-....png。
        //
        // 这里对 uploadPath 做一次归一化（而不是简单写 "file:///" + uploadPath + "/"），
        // 是为了让 application.yml 里无论写不写尾斜杠都能正常工作，避免同一个坑再踩一次。
        String location = uploadPath.endsWith("/") ? uploadPath : uploadPath + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:///" + location);
    }

}
