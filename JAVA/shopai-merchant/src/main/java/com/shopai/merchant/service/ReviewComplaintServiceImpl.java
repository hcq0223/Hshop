package com.shopai.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.Review;
import com.shopai.merchant.entity.ReviewComplaint;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.mapper.ReviewComplaintMapper;
import com.shopai.merchant.mapper.ReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评价投诉（申诉）服务实现
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ReviewComplaintService 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.service，实体/Mapper 同步改为 com.shopai.merchant.*。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;ReviewComplaintMapper, ReviewComplaint&gt; 实现 ReviewComplaintService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class ReviewComplaintServiceImpl extends ServiceImpl<ReviewComplaintMapper, ReviewComplaint> implements ReviewComplaintService {

    private static final Logger log = LoggerFactory.getLogger(ReviewComplaintService.class);

    @Autowired
    private ReviewComplaintMapper complaintMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private MerchantMapper merchantMapper;

    /** 商家发起申诉 */
    @Override
    @Transactional
    public void addComplaint(ReviewComplaint complaint) {
        // 校验是否已有待处理申诉
        int pending = complaintMapper.countPendingByReviewId(complaint.getReviewId());
        if (pending > 0) {
            throw new RuntimeException("该评论已有待处理的申诉");
        }
        // 校验该评论是否属于此商家
        Review review = reviewMapper.selectById(complaint.getReviewId());
        if (review == null) {
            throw new RuntimeException("评论不存在");
        }
        if (!review.getMerchantId().equals(complaint.getMerchantId())) {
            throw new RuntimeException("无权操作，该评论不属于您的商品");
        }
        complaint.setStatus("pending");
        complaint.setCreateTime(LocalDateTime.now());
        complaintMapper.insert(complaint);
    }

    /** 管理员查申诉列表（带商家名称） */
    @Override
    public List<Map<String, Object>> list(String status) {
        LambdaQueryWrapper<ReviewComplaint> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            qw.eq(ReviewComplaint::getStatus, status);
        }
        qw.orderByDesc(ReviewComplaint::getCreateTime);
        List<ReviewComplaint> list = complaintMapper.selectList(qw);

        // 给每个申诉填充商家名称
        return list.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("reviewId", c.getReviewId());
            map.put("merchantId", c.getMerchantId());
            map.put("reason", c.getReason());
            map.put("status", c.getStatus());
            map.put("adminNote", c.getAdminNote());
            map.put("createTime", c.getCreateTime());
            map.put("updateTime", c.getUpdateTime());
            // 查商家名称
            if (c.getMerchantId() != null) {
                Merchant merchant = merchantMapper.selectById(c.getMerchantId());
                map.put("merchantName", merchant != null ? merchant.getShopName() : "");
            } else {
                map.put("merchantName", "");
            }
            return map;
        }).collect(Collectors.toList());
    }

    /** 商家查自己的申诉 */
    @Override
    public List<Map<String, Object>> listByMerchant(Long merchantId) {
        LambdaQueryWrapper<ReviewComplaint> qw = new LambdaQueryWrapper<>();
        qw.eq(ReviewComplaint::getMerchantId, merchantId);
        qw.orderByDesc(ReviewComplaint::getCreateTime);
        List<ReviewComplaint> list = complaintMapper.selectList(qw);
        return list.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("reviewId", c.getReviewId());
            map.put("merchantId", c.getMerchantId());
            map.put("reason", c.getReason());
            map.put("status", c.getStatus());
            map.put("adminNote", c.getAdminNote());
            map.put("createTime", c.getCreateTime());
            map.put("updateTime", c.getUpdateTime());
            map.put("merchantName", "");
            return map;
        }).collect(Collectors.toList());
    }

    /** 管理员驳回申诉 */
    @Override
    @Transactional
    public void reject(Long id, String adminNote) {
        ReviewComplaint complaint = complaintMapper.selectById(id);
        if (complaint == null) throw new RuntimeException("申诉不存在");
        if (!"pending".equals(complaint.getStatus())) throw new RuntimeException("该申诉已处理");

        complaint.setStatus("rejected");
        complaint.setAdminNote(adminNote);
        complaint.setUpdateTime(LocalDateTime.now());
        complaintMapper.updateById(complaint);
    }

    /** 管理员同意申诉（删除评论） */
    @Override
    @Transactional
    public void approve(Long id, String adminNote) {
        ReviewComplaint complaint = complaintMapper.selectById(id);
        if (complaint == null) throw new RuntimeException("申诉不存在");
        if (!"pending".equals(complaint.getStatus())) throw new RuntimeException("该申诉已处理");

        // 删除评论
        reviewService.deleteReview(complaint.getReviewId());

        complaint.setStatus("approved");
        complaint.setAdminNote(adminNote);
        complaint.setUpdateTime(LocalDateTime.now());
        complaintMapper.updateById(complaint);
    }
}
