package com.sthstrange.projectmemo.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端错误/成功消息 → 本地化（P3）。
 * 服务端插件已冻结不改，翻译在客户端做：精确表 → 正则模式 → 「只有…能X」句式 → 原样透传。
 * 单人档 MemoLocal 直接产出已本地化消息，不经过本类。
 */
public final class ServerErrors {

    private ServerErrors() { }

    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    static {
        EXACT.put("找不到工程", "projectmemo.err.projectNotFound");
        EXACT.put("找不到子任务", "projectmemo.err.taskNotFound");
        EXACT.put("找不到材料行", "projectmemo.err.materialNotFound");
        EXACT.put("投影记录不存在", "projectmemo.err.schematicNotFound");
        EXACT.put("没有可导入的材料", "projectmemo.err.noImportableMaterials");
        EXACT.put("工程标题不能为空", "projectmemo.err.projectTitleEmpty");
        EXACT.put("标题不能为空", "projectmemo.err.titleEmpty");
        EXACT.put("标题最长 30 字", "projectmemo.err.titleMax30");
        EXACT.put("标题最长 40 字", "projectmemo.err.titleMax40");
        EXACT.put("子任务标题不能为空", "projectmemo.err.taskTitleEmpty");
        EXACT.put("任务名最长 40 字", "projectmemo.err.collectNameMax40");
        EXACT.put("说明最长 200 字", "projectmemo.err.noteMax200");
        EXACT.put("需求数量需在 1~1000 万", "projectmemo.err.amountRange");
        EXACT.put("创建者默认是管理者，不能移除", "projectmemo.err.creatorManager");
        EXACT.put("收集区域已锁定，请先解锁再改角点", "projectmemo.err.depLocked");
        EXACT.put("收集区域已锁定，请先解锁再清除", "projectmemo.serr.depLockedClear");
        EXACT.put("还没有设置收集区域（先设两个角点）", "projectmemo.err.noDepositArea");
        EXACT.put("区域无效（两个角点不能相同面）", "projectmemo.err.depInvalid");
        EXACT.put("请给收集任务取个名字", "projectmemo.collect.needName");
        EXACT.put("请至少勾选一项材料", "projectmemo.err.pickAtLeastOne");
        EXACT.put("单个收集任务最多 50 项材料", "projectmemo.err.collectMax50");
        EXACT.put("该任务已完成", "projectmemo.err.taskAlreadyDone");
        EXACT.put("该任务未完成", "projectmemo.err.taskNotDone");
        EXACT.put("该任务当前未被认领", "projectmemo.serr.taskNotClaimedNow");
        EXACT.put("这条任务已被认领或已完成", "projectmemo.serr.taskClaimedOrDone");
        EXACT.put("该工程当前不可认领", "projectmemo.serr.notClaimable");
        EXACT.put("该工程当前不可操作", "projectmemo.serr.projectBusy");
        EXACT.put("该工程当前不可登记材料", "projectmemo.serr.noDeliverFrozen");
        EXACT.put("该工程还没有材料行", "projectmemo.serr.noMaterialRows");
        EXACT.put("工程已竣工/归档", "projectmemo.err.projectFrozen");
        EXACT.put("工程已竣工/归档，不能再修改", "projectmemo.err.projectFrozenAdd");
        EXACT.put("工程已竣工/归档，需先解档", "projectmemo.err.projectFrozenUnarchive");
        EXACT.put("工程不是进行中/规划中状态", "projectmemo.serr.notActive");
        EXACT.put("工程已归档，不能再修改", "projectmemo.serr.archivedNoEdit");
        EXACT.put("工程未归档", "projectmemo.serr.notArchived");
        EXACT.put("工程未竣工", "projectmemo.serr.notCompleted");
        EXACT.put("选址已锁定，请先解锁", "projectmemo.serr.locLocked");
        EXACT.put("你没有立项权限（creator 组）", "projectmemo.serr.noCreatePerm");
        EXACT.put("你没有创建子任务权限（creator 组）", "projectmemo.serr.noCreateTaskPerm");
        EXACT.put("只有 creator 组或管理者能改子任务标题", "projectmemo.serr.creatorOrMgrRename");
        EXACT.put("只有 creator 组或管理者能编辑子任务说明", "projectmemo.serr.creatorOrMgrNote");
        EXACT.put("只有工程创建者或 OP 能改管理者列表", "projectmemo.serr.creatorOnlyManagers");
        EXACT.put("删除需要 OP 权限", "projectmemo.serr.deleteOp");
        EXACT.put("归档需要 OP 权限", "projectmemo.serr.archiveOp");
        EXACT.put("解档归档需要 OP 权限", "projectmemo.serr.unarchiveOp");
        EXACT.put("无认领权限", "projectmemo.serr.noClaimPerm");
        EXACT.put("无登记权限", "projectmemo.serr.noDeliverPerm");
        EXACT.put("数量必须是正数", "projectmemo.serr.positive");
        EXACT.put("单次登记不能超过 100 万", "projectmemo.serr.registerMax");
        EXACT.put("单次导入材料行过多（上限 400）", "projectmemo.serr.importMax400");
        EXACT.put("标题 1~30 字", "projectmemo.serr.titleRange");
        EXACT.put("描述最长 300 字", "projectmemo.serr.descMax300");
        EXACT.put("搭建说明最长 300 字", "projectmemo.serr.buildNoteMax300");
        EXACT.put("主手没有物品（先拿着要登记的材料再点）", "projectmemo.serr.handEmpty");
        EXACT.put("状态只能切 planning/active（竣工用 /memo finish）", "projectmemo.serr.stateSwitch");
        EXACT.put("只能取消自己的认领（管理者/OP 可撤销他人）", "projectmemo.serr.unclaimSelfOnly");
        EXACT.put("只能完成自己认领的任务（管理者/OP 可代完）", "projectmemo.serr.doneSelfOnly");
        // ok 消息
        EXACT.put("已合并到已有的同名材料行", "projectmemo.local.mergedRow");
        EXACT.put("区域已切换到当前世界，角点 B 需要重新设置", "projectmemo.local.areaWorldSwitched");
        EXACT.put("角点 A 已设为脚下", "projectmemo.local.cornerASet");
        EXACT.put("角点 B 已设为脚下", "projectmemo.local.cornerBSet");
        EXACT.put("正在核验区域容器…", "projectmemo.local.verifying");
    }

    private static final Object[][] PATTERNS = {
            { Pattern.compile("^「(.+)」已收集完成，无需再认领$"), "projectmemo.err.alreadyCollected" },
            { Pattern.compile("^「(.+)」已有收集任务，认领人: (.+)$"), "projectmemo.err.hasCollectTask" },
            { Pattern.compile("^玩家 (.+) 从未进过服$"), "projectmemo.serr.playerNeverJoined" },
            { Pattern.compile("^(.+) 已经是管理者$"), "projectmemo.err.alreadyManager" },
            { Pattern.compile("^(.+) 已在参与玩家中$"), "projectmemo.err.alreadyParticipant" },
            { Pattern.compile("^找不到管理者 (.+)$"), "projectmemo.serr.managerNotFound" },
            { Pattern.compile("^找不到参与玩家 (.+)$"), "projectmemo.err.participantNotFound" },
            { Pattern.compile("^材料行 #(\\d+) 不存在$"), "projectmemo.err.materialRowNotFound" },
            { Pattern.compile("^未知字段 (.+)$"), "projectmemo.err.unknownField" },
            { Pattern.compile("^区域太大（(\\d+) 方块，上限 4096）$"), "projectmemo.err.depTooBig" },
            { Pattern.compile("^区域所在世界不存在: (.+)$"), "projectmemo.err.depWorldMissing" },
            { Pattern.compile("^收集区域必须在同一世界（角点 A 在 (.+)）$"), "projectmemo.err.depWorldMismatch" },
            { Pattern.compile("^今日立项额度已用完（每天最多 (\\d+) 个大条目）$"), "projectmemo.serr.quotaUsedUp" },
            { Pattern.compile("^工程《(.+)》已竣工/归档，不能再添加$"), "projectmemo.serr.projectFrozenAdd2" },
            { Pattern.compile("^已创建并认领收集任务「(.+)」（(\\d+) 项材料）$"), "projectmemo.local.collectCreated" },
            { Pattern.compile("^已删除投影「(.+)」及其 (\\d+) 行材料$"), "projectmemo.local.schematicDeleted" },
    };

    private static final String MGR_PREFIX = "只有本工程管理者或 OP 能";
    private static final String OP_PREFIX = "只有管理者/OP 能";
    private static final Map<String, String> MGR_ACTIONS = new LinkedHashMap<>();
    private static final Map<String, String> OP_ACTIONS = new LinkedHashMap<>();
    static {
        MGR_ACTIONS.put("编辑", "projectmemo.serr.act.edit");
        MGR_ACTIONS.put("改选址", "projectmemo.serr.act.editLoc");
        MGR_ACTIONS.put("改选址可见性", "projectmemo.serr.act.editLocVisibility");
        MGR_ACTIONS.put("清除选址", "projectmemo.serr.act.clearLoc");
        MGR_ACTIONS.put("核验收集", "projectmemo.serr.act.verify");
        MGR_ACTIONS.put("设置收集区域", "projectmemo.serr.act.setDeposit");
        MGR_ACTIONS.put("清除收集区域", "projectmemo.serr.act.clearDeposit");
        MGR_ACTIONS.put("操作选址锁", "projectmemo.serr.act.locLock");
        MGR_ACTIONS.put("操作角点锁", "projectmemo.serr.act.depLock");
        MGR_ACTIONS.put("解档", "projectmemo.serr.act.unarchive");
        OP_ACTIONS.put("删子任务", "projectmemo.serr.act.deleteTasks");
        OP_ACTIONS.put("删材料行", "projectmemo.serr.act.deleteRows");
        OP_ACTIONS.put("导入投影", "projectmemo.serr.act.importSchematics");
        OP_ACTIONS.put("改需求", "projectmemo.serr.act.changeNeed");
        OP_ACTIONS.put("添加材料行", "projectmemo.serr.act.addRows");
        OP_ACTIONS.put("竣工", "projectmemo.serr.act.complete");
        OP_ACTIONS.put("管理投影", "projectmemo.serr.act.manageSchematics");
        OP_ACTIONS.put("编辑参与玩家", "projectmemo.serr.act.editParticipants");
        OP_ACTIONS.put("重开任务", "projectmemo.serr.act.reopenTasks");
    }

    /** 翻译服务端消息；无匹配时原样返回。 */
    public static String translate(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String key = EXACT.get(msg);
        if (key != null) return L10n.get(key);
        // 核验完成（含可选的自动完成后缀）
        Matcher verify = Pattern.compile("^核验完成：区域物品 (\\d+) 件，更新 (\\d+) 行进度(?:；收集任务自动完成: (.+))?$").matcher(msg);
        if (verify.matches()) {
            String out = L10n.get("projectmemo.local.verifyDone", verify.group(1), verify.group(2));
            if (verify.group(3) != null) {
                String[] names = verify.group(3).split("、");
                out += L10n.get("projectmemo.local.autoDone", String.join(L10n.get("projectmemo.common.listSep"), names));
            }
            return out;
        }
        Matcher imp = Pattern.compile("^导入完成：新增 (\\d+) 行(?:，合并 (\\d+) 行)?$").matcher(msg);
        if (imp.matches()) {
            String out = L10n.get("projectmemo.local.importDone", imp.group(1));
            if (imp.group(2) != null) out += L10n.get("projectmemo.local.importMerged", imp.group(2));
            return out;
        }
        if (msg.startsWith(MGR_PREFIX)) {
            String actKey = MGR_ACTIONS.get(msg.substring(MGR_PREFIX.length()));
            if (actKey != null) return L10n.get("projectmemo.serr.managerOnlyAction", L10n.get(actKey));
        }
        if (msg.startsWith(OP_PREFIX)) {
            String actKey = OP_ACTIONS.get(msg.substring(OP_PREFIX.length()));
            if (actKey != null) return L10n.get("projectmemo.serr.opManagerOnlyAction", L10n.get(actKey));
        }
        for (Object[] r : PATTERNS) {
            Matcher m = ((Pattern) r[0]).matcher(msg);
            if (m.matches()) {
                Object[] args = new Object[m.groupCount()];
                for (int i = 0; i < args.length; i++) args[i] = m.group(i + 1);
                return L10n.get((String) r[1], args);
            }
        }
        return msg;
    }
}
