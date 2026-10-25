package io.github.qqmodulemanager.bridge;

import java.util.*;

/** Audited capability mappings. A provider's alternatives are ordered, never initialized together. */
public final class FeatureCatalog {
    private FeatureCatalog() {}
    public static final class Feature {
        public final String id, title, category, detail;
        public final boolean allProcesses;
        private final Map<String,List<String>> implementations = new LinkedHashMap<>();
        Feature(String id,String title,String category,String detail,boolean allProcesses,String[]... providers) {
            this.id=id;this.title=title;this.category=category;this.detail=detail;this.allProcesses=allProcesses;
            for(String[] p:providers)implementations.put(p[0],Collections.unmodifiableList(Arrays.asList(Arrays.copyOfRange(p,1,p.length))));
        }
        public List<String> modules(){return new ArrayList<>(implementations.keySet());}
        public List<String> classes(String module){return implementations.getOrDefault(module,Collections.emptyList());}
        public boolean coldSwitch(){return !id.equals("message_count");}
    }
    public static final List<Feature> ALL=Collections.unmodifiableList(Arrays.asList(
        new Feature("anti_revoke","防撤回","消息","保留各实现的提示形式及离线覆盖；切换后完全重启 QQ。",false,new String[]{"qauxv","cc.ioctl.hook.msg.RevokeMsgHook"},new String[]{"qfun","me.yxp.qfun.hook.msg.AntiRevoke"},new String[]{"ono","moe.ono.hooks.item.chat.HoldRevokeMsg"}),
        new Feature("message_count","完整消息数量","消息","QA 额外覆盖返回按钮等位置；角标切换可即时生效。",false,new String[]{"qauxv","cc.ioctl.hook.msg.ShowMsgCount"},new String[]{"qfun","me.yxp.qfun.hook.social.ExactMsgCount"}),
        new Feature("default_bubble","默认消息气泡","外观","气泡与字体分开管理；QFun 作用于他人的消息。",false,new String[]{"qauxv","cc.ioctl.hook.chat.DefaultBubbleHook"},new String[]{"qfun","me.yxp.qfun.hook.chat.DefaultBubble"}),
        new Feature("default_font","默认消息字体","外观","保留字体以外的消息属性；QFun 作用于他人的消息。",false,new String[]{"qauxv","cc.ioctl.hook.chat.DefaultFont"},new String[]{"qfun","me.yxp.qfun.hook.chat.DefaultFont"}),
        new Feature("edit_resend","编辑重发消息","消息","QA 支持文字重发，图文分支尚未实现；QFun 支持恢复图文到输入框。",false,new String[]{"qauxv","me.hd.hook.menu.EditTextContent"},new String[]{"qfun","me.yxp.qfun.hook.chat.FastReSend"}),
        new Feature("forward_voice","语音转发与保存","消息","保留各模块的菜单入口和支持的语音类型。",false,new String[]{"qauxv","cc.ioctl.hook.msg.PttForwardHook"},new String[]{"qfun","me.yxp.qfun.hook.chat.ForwardPtt"}),
        new Feature("batch_recall","多选撤回","消息","使用各模块原有多选入口。",false,new String[]{"qauxv","me.ketal.hook.MultiActionHook"},new String[]{"qfun","me.yxp.qfun.hook.chat.MultiRecall"}),
        new Feature("reply_no_at","回复不自动艾特","消息","移除回复时自动添加的艾特。",false,new String[]{"qauxv","cc.ioctl.hook.ui.chat.ReplyNoAtHook"},new String[]{"qfun","me.yxp.qfun.hook.chat.RemoveReplyAt"}),
        new Feature("repeat_message","消息复读","消息","QA 优先使用复读机 Plus；入口和细项沿用所选实现。",false,new String[]{"qauxv","cc.hicore.hook.RepeaterPlus","cc.ioctl.hook.ui.chat.RepeaterHook"},new String[]{"qfun","me.yxp.qfun.hook.chat.RepeatMsg"}),
        new Feature("at_target","显示艾特对象","消息","显示艾特指向的用户信息。",false,new String[]{"qauxv","me.ketal.hook.ShowMsgAt"},new String[]{"qfun","me.yxp.qfun.hook.chat.ShowAtTarget"}),
        new Feature("emoticon_sort","收藏表情排序","消息","QA 的排序方式与 QFun 的使用频率排序分别使用原配置。",false,new String[]{"qauxv","cc.microblock.hook.FavEmoticonSortBy"},new String[]{"qfun","me.yxp.qfun.hook.chat.SortEmoticonsByUsage"}),
        new Feature("system_emoji","系统 Emoji","外观","使用系统 Emoji 渲染。",false,new String[]{"qauxv","me.singleneuron.hook.SystemEmoji"},new String[]{"qfun","me.yxp.qfun.hook.chat.SystemEmoji"}),
        new Feature("left_swipe_reply","解除左滑回复限制","消息","允许更多类型的消息左滑回复。",false,new String[]{"qauxv","cc.hicore.hook.UnlockLeftSlipLimit"},new String[]{"qfun","me.yxp.qfun.hook.chat.UnlockLeftSwipeReply"}),
        new Feature("device_model","自定义设备机型","设备","机型值在对应模块中配置；需要完全重启 QQ。",true,new String[]{"qauxv","cc.ioctl.hook.misc.CustomDeviceModel"},new String[]{"qfun","me.yxp.qfun.hook.device.CustomDevice"}),
        new Feature("multi_window","伪装非多窗口状态","设备","在支持的进程中隐藏分屏状态。",true,new String[]{"qauxv","me.ketal.hook.FakeMultiWindowStatus"},new String[]{"qfun","me.yxp.qfun.hook.device.DisableSplitScreenLimit"}),
        new Feature("tablet_mode","平板模式","设备","使用平板设备模式；需要完全重启 QQ。",true,new String[]{"qauxv","cc.ioctl.hook.experimental.ForcePadMode"},new String[]{"qfun","me.yxp.qfun.hook.device.TabletMode"}),
        new Feature("apk_rename","APK 上传重命名","文件","文件名模板沿用所选模块配置。",false,new String[]{"qauxv","cc.ioctl.hook.file.BaseApk"},new String[]{"qfun","me.yxp.qfun.hook.file.AutoRemarkApk"}),
        new Feature("original_photo","自动发送原图","文件","自动选择发送原图。",false,new String[]{"qauxv","xyz.nextalone.hook.AutoSendOriginalPhoto"},new String[]{"qfun","me.yxp.qfun.hook.file.AutoSendOriginalPic"}),
        new Feature("download_redirect","下载文件重定向","文件","QA 使用已配置的目录，QFun 使用 Download/QQ；先在所选模块配置路径。",true,new String[]{"qauxv","cc.ioctl.hook.experimental.FileRecvRedirect"},new String[]{"qfun","me.yxp.qfun.hook.file.DownloadRedirect"}),
        new Feature("speech_text","语音自动转文本","消息","群聊范围等细项使用原模块配置。",false,new String[]{"qauxv","io.github.nakixii.hook.AutoSpeechToText"},new String[]{"qfun","me.yxp.qfun.hook.msg.AutoSpeechToText"}),
        new Feature("emotion_image","表情以图片打开","消息","将表情按普通图片查看。",false,new String[]{"qauxv","cc.ioctl.hook.chat.EmoPicHook"},new String[]{"qfun","me.yxp.qfun.hook.msg.EmotionToPic"}),
        new Feature("flash_photo","闪照以图片打开","消息","沿用所选模块的闪照处理。",false,new String[]{"qauxv","cc.ioctl.hook.msg.FlashPicHook"},new String[]{"qfun","me.yxp.qfun.hook.msg.FlashPicDecrypt"}),
        new Feature("local_text","修改本地消息文本","消息","仅修改本地显示，编辑入口沿用所选模块。",false,new String[]{"qfun","me.yxp.qfun.hook.msg.ModifyMsgContent"},new String[]{"ono","moe.ono.hooks.item.entertainment.ModifyTextMessage"}),
        new Feature("image_summary","自定义图片外显","消息","文字和适用图片类型沿用原模块配置。",false,new String[]{"qauxv","cc.microblock.hook.ImageCustomSummary"},new String[]{"qfun","me.yxp.qfun.hook.msg.ModifyPicSummary"}),
        new Feature("hot_patch","屏蔽 QQ 热更新","设备","两者覆盖的热补丁引擎范围不同。",true,new String[]{"qauxv","cc.ioctl.hook.misc.DisableHotPatch"},new String[]{"qfun","me.yxp.qfun.hook.patch.DisableHotPatch"}),
        new Feature("interactive_pop","屏蔽消息交互弹窗","净化","屏蔽特定消息触发的交互式弹窗。",false,new String[]{"qauxv","com.xiaoniu.hook.DisableInteractivePop"},new String[]{"qfun","me.yxp.qfun.hook.purify.AntiInteractivePop"}),
        new Feature("link_preview","屏蔽链接预览","净化","屏蔽消息中的网页预览信息。",false,new String[]{"qauxv","im.mingxi.BlockLinkInfo"},new String[]{"qfun","me.yxp.qfun.hook.purify.AntiLinkPreview"}),
        new Feature("swipe_right","屏蔽聊天右滑","净化","屏蔽聊天界面的右滑入口。",false,new String[]{"qauxv","me.hd.hook.simplify.chat.other.DisableSwipeRight"},new String[]{"qfun","me.yxp.qfun.hook.purify.BlockRightSwipe"}),
        new Feature("diy_card","屏蔽 DIY 名片","净化","按普通资料卡显示。",false,new String[]{"qauxv","cc.ioctl.hook.ui.profile.RemoveDiyCard"},new String[]{"qfun","me.yxp.qfun.hook.purify.RemoveDIYCard"}),
        new Feature("reaction_menu","移除表情回应菜单","净化","只管理回应入口，不与灰字提示或回应数量限制合并。",false,new String[]{"qauxv","me.hd.hook.simplify.ui.chat.msg.RemoveEmoReplyMenu"},new String[]{"qfun","me.yxp.qfun.hook.purify.RemoveEmoReplyMenu"}),
        new Feature("qq_show_avatar","屏蔽 QQ 秀头像","净化","QFun 只屏蔽他人的 QQ 秀；不与隐藏 QQ 秀按钮合并。",false,new String[]{"qauxv","me.hd.hook.simplify.ui.chat.msg.DisableAiAvatar"},new String[]{"qfun","me.yxp.qfun.hook.purify.RemoveQQShow"}),
        new Feature("browser_restrict","解除内置浏览器拦截","浏览器","沿用各模块已有的浏览器处理。",false,new String[]{"qauxv","cc.ioctl.hook.misc.BrowserRestrictMitigation"},new String[]{"qfun","me.yxp.qfun.hook.purify.RemoveRiskWebpageBlock"}),
        new Feature("qr_login","解除相册扫码登录限制","浏览器","去除相册来源的扫码登录检查。",false,new String[]{"qauxv","me.ketal.hook.RemoveQRLoginAuth"},new String[]{"qfun","me.yxp.qfun.hook.qrcode.RemoveQrCodeCheck"}),
        new Feature("qr_wait","跳过扫码确认等待","浏览器","跳过扫码确认的等待时间。",false,new String[]{"qauxv","cc.ioctl.hook.experimental.QrLoginAuthSecCheckMitigation"},new String[]{"qfun","me.yxp.qfun.hook.qrcode.SkipScanWaitTime"}),
        new Feature("profile_likes","一键多次点赞","社交","QA 一键 20 赞，QFun 尝试当日上限；以平台实际限制为准。",false,new String[]{"qauxv","cc.ioctl.hook.profile.OneTapTwentyLikes"},new String[]{"qfun","me.yxp.qfun.hook.social.OneClickLike"}),
        new Feature("forward_limit","解除转发人数限制","消息","移除转发选择界面的联系人数量限制。",false,new String[]{"qauxv","io.github.memory2314.hook.RemoveShareLimit"},new String[]{"qfun","me.yxp.qfun.hook.social.RemoveForwardLimit"}),
        new Feature("blocked_group","查看封禁群聊天","群聊","允许查看本地历史；各实现的检查覆盖不同。",false,new String[]{"qauxv","me.hd.hook.ChangeGroupStatus"},new String[]{"qfun","me.yxp.qfun.hook.troop.AllowViewBlockedTroop"}),
        new Feature("atlist_sort","艾特列表排序","群聊","排序规则使用所选模块的配置。",false,new String[]{"qauxv","me.ketal.hook.SortAtPanel","top.suzhelan.hook.item.SortAtList"},new String[]{"qfun","me.yxp.qfun.hook.troop.SortingAtList"}),
        new Feature("miniapp_jump","小程序卡跳转 APP","浏览器","跳转规则使用所选模块配置；不与小程序转链接合并。",false,new String[]{"qauxv","top.xunflash.hook.MiniAppDirectJump"},new String[]{"ono","moe.ono.hooks.item.chat.MiniAppCardDirectJumpApp"}),
        new Feature("sticker_panel","表情面板","消息","沿用各模块原有表情面板及设置。",false,new String[]{"qauxv","cc.hicore.hook.stickerPanel.Hooker.StickerPanelEntryHooker"},new String[]{"ono","moe.ono.hooks.item.chat.StickerPanelEntry"}),
        new Feature("read_position","上次阅读位置","群聊","QFun 自动跳转群聊位置，ono 提供灰字点击定位。",false,new String[]{"qfun","me.yxp.qfun.hook.troop.RememberLastRead"},new String[]{"ono","moe.ono.hooks.item.chat.ChatScrollMemory"}),
        new Feature("shortcut_bar","聊天快捷栏精简","净化","QA 隐藏快捷栏，QFun 按已配置的按钮列表精简。",false,new String[]{"qauxv","me.hd.hook.simplify.chat.other.HideShortcutBar"},new String[]{"qfun","me.yxp.qfun.hook.purify.ShortcutBarCustom"})
    ));
    private static final Map<String,Feature> BY_ID=new HashMap<>();
    private static final Map<String,String> BY_CLASS=new HashMap<>();
    static {
        for(Feature f:ALL) {
            if(BY_ID.put(f.id,f)!=null)throw new IllegalStateException("Duplicate feature: "+f.id);
            for(List<String> classes:f.implementations.values())for(String name:classes)
                if(BY_CLASS.put(name,f.id)!=null)throw new IllegalStateException("Ambiguous provider: "+name);
        }
    }
    public static Feature get(String id){return BY_ID.get(id);}
    public static String forClass(String name){return BY_CLASS.getOrDefault(name,"");}
}
