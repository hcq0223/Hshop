package com.shopai.merchant.service;

import com.shopai.merchant.entity.ReviewComplaint;

import java.util.List;
import java.util.Map;

/**
 * 评价投诉（申诉）服务接口
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.ReviewComplaintService 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.service，实体/Mapper 同步改为 com.shopai.merchant.*。
 * <p>
 * 分层改造（2026-10-05）：本接口 + ReviewComplaintServiceImpl（extends ServiceImpl&lt;ReviewComplaintMapper, ReviewComplaint&gt;）标准分层。
 * 注意：本接口 list(String) 是业务方法（按状态查申诉），与 IService.list()/list(Wrapper) 构成重载。
 */
public interface ReviewComplaintService {

    /** 商家发起申诉 */
    void addComplaint(ReviewComplaint complaint);

    /** 管理员查申诉列表（带商家名称） */
    List<Map<String, Object>> list(String status);

    /** 商家查自己的申诉 */
    List<Map<String, Object>> listByMerchant(Long merchantId);

    /** 管理员驳回申诉 */
    void reject(Long id, String adminNote);

    /** 管理员同意申诉（删除评论） */
    void approve(Long id, String adminNote);
}
