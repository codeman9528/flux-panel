package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.ForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardController extends BaseController {

    @Autowired
    private ForwardService forwardService;

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody ForwardDto forwardDto) {
        return forwardService.createForward(forwardDto);
    }

    @LogAnnotation
    @PostMapping("/list")
    public R readAll() {
        return forwardService.getAllForwards();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody ForwardUpdateDto forwardUpdateDto) {
        return forwardService.updateForward(forwardUpdateDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.deleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/force-delete")
    public R forceDelete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.forceDeleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/pause")
    public R pause(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.pauseForward(id);
    }

    @LogAnnotation
    @PostMapping("/resume")
    public R resume(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return forwardService.resumeForward(id);
    }

    /**
     * 转发诊断功能
     * @param params 包含forwardId的参数
     * @return 诊断结果
     */
    @LogAnnotation
    @PostMapping("/diagnose")
    public R diagnoseForward(@RequestBody Map<String, Object> params) {
        Long forwardId = Long.valueOf(params.get("forwardId").toString());
        return forwardService.diagnoseForward(forwardId);
    }

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数，每个元素包含id和inx
     * @return 更新结果
     */
    @LogAnnotation
    @PostMapping("/update-order")
    public R updateForwardOrder(@RequestBody Map<String, Object> params) {
        return forwardService.updateForwardOrder(params);
    }

    @LogAnnotation
    @PostMapping("/batch-delete")
    public R batchDelete(@RequestBody Map<String, Object> params) {
        return forwardService.batchDeleteForward(parseIds(params.get("ids")));
    }

    @LogAnnotation
    @PostMapping("/batch-pause")
    public R batchPause(@RequestBody Map<String, Object> params) {
        return forwardService.batchPauseForward(parseIds(params.get("ids")));
    }

    @LogAnnotation
    @PostMapping("/batch-resume")
    public R batchResume(@RequestBody Map<String, Object> params) {
        return forwardService.batchResumeForward(parseIds(params.get("ids")));
    }

    @LogAnnotation
    @PostMapping("/batch-move")
    public R batchMove(@RequestBody Map<String, Object> params) {
        return forwardService.batchMoveForward(parseIds(params.get("ids")), parseTunnelId(params.get("tunnelId")));
    }

    @LogAnnotation
    @PostMapping("/batch-force-delete")
    public R batchForceDelete(@RequestBody Map<String, Object> params) {
        return forwardService.batchForceDeleteForward(parseIds(params.get("ids")));
    }

    /**
     * 解析请求中的 ids 数组为 List<Long>，非法元素跳过（避免非数字输入导致 500）
     */
    private List<Long> parseIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?>) {
            for (Object o : (List<?>) raw) {
                if (o == null) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(o.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 id
                }
            }
        }
        return ids;
    }

    /**
     * 解析 tunnelId，非法或为空时返回 null（由 service 层给出友好错误）
     */
    private Integer parseTunnelId(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
