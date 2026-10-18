package com.shopai.user.controller;

import com.shopai.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 通用控制器 — 文件上传
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Controller.CommonController 迁移至 shopai-user 服务。
 * 路径不变（/api/common/**）。原单体上传后返回可访问 URL（/uploads/xxx.jpg），
 * 静态资源 /uploads/** 由网关路由转发到本服务（本服务通过 shopai-common 的 WebMvcConfig
 * 将 /uploads/** 映射到本地 upload.path 目录）。
 * <p>
 * 鉴权改造：原接口仅从 Authorization 头取 token 但未强校验（非登录也可传图），
 * 拆分后保持公开（网关白名单放行 /api/common/**），不改动原业务语义。
 */
@RestController
@RequestMapping("/api/common")
public class CommonController {

    @Value("${upload.path:D:/ShopAi/VorI/Images}")
    private String uploadPath;

    /**
     * 上传文件（图片），返回可访问的 URL
     * 旧路径：/api/common/upload → 新路径：/api/common/upload（不变）
     */
    @PostMapping("/upload")
    public Result<?> upload(@RequestHeader(value = "Authorization", required = false) String token,
                            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error("文件为空");

        // 校验类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.error("只允许上传图片文件");
        }

        // 生成唯一文件名
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf("."));
        }
        String fileName = UUID.randomUUID().toString() + ext;

        try {
            Path dir = Paths.get(uploadPath);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path dest = dir.resolve(fileName);
            file.transferTo(dest.toFile());

            // 返回可访问 URL（/uploads/xxx.jpg）
            String url = "/uploads/" + fileName;
            return Result.ok(url);
        } catch (IOException e) {
            return Result.error("上传失败: " + e.getMessage());
        }
    }
}
