package com.icecode.workbench.trash;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * 回收站（US-1.5「软删除，30 天内可恢复」）。
 *
 * <p>路由放在 {@code /api/v1/trash} 下，落在 {@code LoginInterceptor} 的 {@code /api/v1/**}
 * 拦截范围内——回收站里能看到已删除的原始内容，必须和其它业务接口一样要求登录。</p>
 */
@RestController
@RequestMapping("/api/v1/trash")
public class TrashController {

    private final TrashService trashService;

    public TrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping
    public ApiResponse<List<TrashItemVO>> list() {
        return ApiResponse.success(trashService.list());
    }

    /**
     * 恢复一条已删除记录。
     *
     * <p>用 POST 而不是 PUT/PATCH：这个动作不是「替换资源表示」，而是「把一条记录从已删状态
     * 翻转回在用状态」，语义上是命令；同时它带副作用（写时间线），本项目的其它状态翻转
     * （任务换状态、备忘归档、日程确认）也统一用 POST。</p>
     *
     * <p>{@code type} 用字符串而不是数字枚举：前端要按类型拼不同的 toast 文案，
     * 字符串可读性更好；取值校验在 {@link TrashService#restore} 里做，非法值会连合法取值一起报出来。</p>
     */
    @PostMapping("/{type}/{id}/restore")
    public ApiResponse<TrashRestoreVO> restore(@PathVariable String type, @PathVariable long id) {
        return ApiResponse.success(trashService.restore(type, id));
    }

    /**
     * 彻底删除一条（物理删除，无法恢复）。
     *
     * <p>这里用 DELETE 而不是 POST：删掉的**就是一个资源**（回收站里的那条记录），语义与 REST
     * 完全对应；而 restore 是「把状态翻转回去」，是命令不是资源删除，所以它才是 POST。
     * 两条路径同形、只差最后那段动词，调用方一眼能看出它们作用在同一条记录上。</p>
     *
     * <p>要提醒的是它**不是**「把条目从回收站里挪走」这种可逆动作 —— 删的是原始记录本身。
     * 所以前端必须二次确认，文案必须写「无法恢复」（静态检查守着这条分界线）。</p>
     */
    @DeleteMapping("/{type}/{id}")
    public ApiResponse<TrashPurgeVO> purge(@PathVariable String type, @PathVariable long id) {
        return ApiResponse.success(trashService.purge(type, id));
    }

    /**
     * 清空回收站：把列表里看得见的那些一次性彻底删除。
     *
     * <p>没有 body 也没有查询参数 —— 作用范围由「回收站里现在有什么」决定，
     * 让前端传一份 id 清单反而会引入「清单和实际不一致」这种没必要的失败模式。</p>
     */
    @DeleteMapping
    public ApiResponse<TrashPurgeAllVO> purgeAll() {
        return ApiResponse.success(trashService.purgeAll());
    }
}
