package com.stepos.whisper.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stepos.whisper.R;
import com.stepos.whisper.Settings;
import com.stepos.whisper.data.AppDatabase;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.User;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserDao;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.databinding.ActivityMainBinding;
import com.stepos.whisper.databinding.DialogThresholdBinding;
import com.stepos.whisper.databinding.SheetMoreBinding;
import com.stepos.whisper.glasses.GlassesManager;
import com.stepos.whisper.glasses.GlassesPairingActivity;
import com.stepos.whisper.service.RecordingService;
import com.stepos.whisper.service.ServiceState;
import com.stepos.whisper.service.TranscriptLine;
import com.stepos.whisper.voiceprint.VoiceprintLibrary;
import com.stepos.whisper.voiceprint.XfVoiceprintClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏主界面：上 40% 是待办或事实卡片（顶部切换；不规则堆叠、循环滑动），下 60% 是实时转写。
 * <ul>
 *   <li>左上角 AI 状态标签可以点，等于“让 AI 立即识别”；</li>
 *   <li>右下角「更多」打开底部面板：开关类设置在上，操作在下，清空聊天这种危险操作放最后；</li>
 *   <li>转写里点一句话看这个人的事项（陌生人则命名），长按改这句话的归属；</li>
 *   <li>聊天截图里有昵称等着指定时，转写上方出现一条提示，点进去指定。</li>
 * </ul>
 * 还没有录入机主声纹时先弹窗问是直接录还是稍后再说。
 */
public class MainActivity extends AppCompatActivity {
    /** 从灵动岛熟人提醒点“查看”进入时带上，进入后全屏弹出该用户的全部卡片。 */
    public static final String EXTRA_FOCUS_USER = "focus_user";

    private ActivityMainBinding mBinding;
    private TranscriptAdapter mTranscriptAdapter;
    private UserChipAdapter mUserAdapter;
    private final Map<Long, UserDao.UserSummary> mUsers = new HashMap<>();
    private ServiceState.Status mStatus = ServiceState.Status.STOPPED;
    private ServiceState.AiStatus mAiStatus;
    /** 上方卡片区当前显示待办还是事实。 */
    private int mCardKind = UserFact.KIND_TODO;
    private List<FactCard> mPendingCards = new ArrayList<>();
    /** 等着机主指定昵称的截图任务。 */
    private List<Long> mAliasJobs = new ArrayList<>();
    /**
     * 机主声纹的检查已经完成（有机主，或机主选了稍后再说）。之后每次回到主界面，
     * 录音服务不在（比如重新录入声纹时被停掉又中途退出）就重新拉起。
     */
    private boolean mOwnerChecked;
    private BottomSheetDialog mSheet;

    private final ActivityResultLauncher<String[]> mPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        enterFullscreen();
        avoidDisplayCutout();

        setupCards();
        setupUsers();
        setupTranscript();
        mBinding.toggle.setOnClickListener(v -> toggleRecording());
        mBinding.more.setOnClickListener(v -> showMoreSheet());
        mBinding.aiStatusBox.setOnClickListener(v -> extractAll());
        mBinding.aliasBanner.setOnClickListener(v -> openPendingAlias());
        mBinding.status.setOnClickListener(v -> showStatusDetail());
        ServiceState.aiStatus().observe(this, this::renderAiStatus);
        handleFocusIntent(getIntent());

        ServiceState.status().observe(this, status -> {
            mStatus = status;
            renderStatus();
        });
        ServiceState.error().observe(this, e -> renderStatus());
        ServiceState.partial().observe(this, text -> mBinding.partial.setText(text));
        ServiceState.strangerPrompt().observe(this, this::promptStranger);
        ServiceState.aliasPrompt().observe(this, this::promptAlias);
        ServiceState.aliasWaiting().observe(this, this::renderAliasBanner);
        AppDatabase.IO.execute(() -> {
            User owner = AppDatabase.get(this).userDao().getOwner();
            boolean noLocalUsers = AppDatabase.get(this).userDao().getAll().isEmpty();
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (owner == null && noLocalUsers) {
                    // 本地一个用户都没有：可能刚重装，云端声纹还在，问问要不要恢复。
                    maybeOfferCloudRestore();
                } else if (owner == null) {
                    promptOwnerEnroll();
                } else {
                    mOwnerChecked = true;
                    ensurePermissionsAndStart();
                }
            });
        });
    }

    /**
     * 卸载/清数据后本地与云端声纹失联（云端数据不随卸载清除）。查到云端还有特征时，
     * 按 featureInfo 里存的名字在本地重建用户和 featureId 映射；也可以清空云端重新录入。
     */
    private void maybeOfferCloudRestore() {
        VoiceprintLibrary lib = new VoiceprintLibrary(Settings.getSpeakerThreshold(this));
        lib.queryCloud((entries, err) -> runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            if (err != null || entries == null || entries.isEmpty()) {
                promptOwnerEnroll();
                return;
            }
            StringBuilder names = new StringBuilder();
            for (XfVoiceprintClient.FeatureEntry e : entries) {
                String name = restoreName(e.featureInfo);
                if (name != null && names.indexOf(name) < 0) {
                    if (names.length() > 0) {
                        names.append('、');
                    }
                    names.append(name);
                }
            }
            final List<XfVoiceprintClient.FeatureEntry> cloud = entries;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("发现云端声纹")
                    .setMessage("云端保存着这些人的声纹：" + names + "。\n恢复后无需重新朗读。"
                            + "\n\n提示：卸载应用不会清除云端声纹，只有这里能清。")
                    .setCancelable(false)
                    .setPositiveButton("恢复", (d, w) ->
                            AppDatabase.IO.execute(() -> restoreFromCloud(cloud)))
                    .setNegativeButton("清空重来", (d, w) -> {
                        List<String> ids = new ArrayList<>();
                        for (XfVoiceprintClient.FeatureEntry e : cloud) {
                            ids.add(e.featureId);
                        }
                        VoiceprintLibrary.deleteFeaturesQuietly(ids);
                        promptOwnerEnroll();
                    })
                    .setNeutralButton("稍后再说", (d, w) -> {
                        mOwnerChecked = true;
                        ensurePermissionsAndStart();
                    })
                    .show();
        }));
    }

    /** featureInfo 格式 “名字|机主|日期” 或 “名字|日期”，取第一段。 */
    private static String restoreName(String featureInfo) {
        if (featureInfo == null || featureInfo.isEmpty()) {
            return null;
        }
        int sep = featureInfo.indexOf('|');
        String name = sep < 0 ? featureInfo : featureInfo.substring(0, sep);
        return name.trim().isEmpty() ? null : name.trim();
    }

    /** db-io 线程：按名字分组重建用户，featureInfo 带机主标记的作为机主。 */
    private void restoreFromCloud(List<XfVoiceprintClient.FeatureEntry> entries) {
        AppDatabase db = AppDatabase.get(this);
        Map<String, User> byName = new HashMap<>();
        long now = System.currentTimeMillis();
        for (XfVoiceprintClient.FeatureEntry e : entries) {
            String name = restoreName(e.featureInfo);
            if (name == null) {
                name = "陌生人";
            }
            boolean owner = e.featureInfo != null && e.featureInfo.contains("|机主");
            User user = byName.get(name);
            if (user == null) {
                user = new User();
                user.name = name;
                user.isOwner = owner;
                user.createdAt = now;
                user.lastSeenAt = now;
                user.color = UserColors.pickUnused(db.userDao().getAllColors());
                user.id = db.userDao().insert(user);
                byName.put(name, user);
            }
            if (owner && db.userDao().getOwner() == null) {
                user.isOwner = true;
            }
            user.featureIds = VoiceprintLibrary.appendFeatureId(user.featureIds, e.featureId);
            db.userDao().update(user);
        }
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.reloadUsers();
        }
        runOnUiThread(() -> {
            Toast.makeText(this, "已从云端恢复声纹", Toast.LENGTH_SHORT).show();
            mOwnerChecked = true;
            ensurePermissionsAndStart();
        });
    }

    /** 还没录本人声纹：直接去录，或者稍后再说（照常录音，之后从“更多”里录入）。 */
    private void promptOwnerEnroll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.enroll_title)
                .setMessage(R.string.enroll_prompt_message)
                .setCancelable(false)
                .setPositiveButton(R.string.enroll_prompt_now, (d, w) -> {
                    startActivity(VoiceEnrollActivity.createOwnerIntent(this, false));
                    finish();
                })
                .setNegativeButton(R.string.enroll_prompt_later, (d, w) -> {
                    mOwnerChecked = true;
                    ensurePermissionsAndStart();
                })
                .show();
    }

    private void handleFocusIntent(Intent intent) {
        long userId = intent.getLongExtra(EXTRA_FOCUS_USER, -1);
        if (userId < 0) {
            return;
        }
        intent.removeExtra(EXTRA_FOCUS_USER);
        RecordingService service = RecordingService.getInstance();
        if (service != null) {
            service.showFocus(userId);
            return;
        }
        // 进程已经被杀、服务还没起来（比如很久以后才点灵动岛上的“查看”）：不依赖服务，直接弹出这个人的事项。
        AppDatabase.IO.execute(() -> {
            User user = AppDatabase.get(this).userDao().get(userId);
            if (user != null) {
                runOnUiThread(() -> FactsDialog.show(this, userId, user.name, true));
            }
        });
    }

    /** 听到新陌生人：弹出命名窗口，填名字、颜色或关联到已有用户。 */
    private void promptStranger(long userId) {
        if (userId == ServiceState.NO_PROMPT) {
            return;
        }
        ServiceState.clearStrangerPrompt(userId);
        startActivity(SpeakerEditActivity.createPromptIntent(this, userId));
    }

    /** 聊天截图里出现了没对应过的昵称，而且此时就在主界面：直接弹出指定窗口，不用去点灵动岛。 */
    private void promptAlias(long jobId) {
        if (jobId == ServiceState.NO_PROMPT) {
            return;
        }
        ServiceState.clearAliasPrompt(jobId);
        startActivity(ChatAliasActivity.createIntent(this, jobId));
    }

    private void renderAliasBanner(List<Long> jobs) {
        mAliasJobs = jobs == null ? new ArrayList<>() : jobs;
        mBinding.aliasBanner.setVisibility(mAliasJobs.isEmpty() ? View.GONE : View.VISIBLE);
        if (!mAliasJobs.isEmpty()) {
            mBinding.aliasBannerText.setText(getString(R.string.alias_banner, mAliasJobs.size()));
        }
    }

    private void openPendingAlias() {
        if (mAliasJobs.isEmpty()) {
            return;
        }
        startActivity(ChatAliasActivity.createIntent(this, mAliasJobs.get(0)));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFocusIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ServiceState.setUiVisible(true);
        // 重新录入机主声纹时录音服务会被停掉；录完（或中途退出）回到这里都要把它拉起来。
        if (mOwnerChecked && RecordingService.getInstance() == null && hasRecordPermission()) {
            startRecordingService();
        }
    }

    @Override
    protected void onStop() {
        ServiceState.setUiVisible(false);
        if (mSheet != null && mSheet.isShowing()) {
            mSheet.dismiss();
        }
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** 主题允许画进挖孔区域（shortEdges），背景铺满，内容按挖孔安全区留边，避开前置摄像头。 */
    private void avoidDisplayCutout() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            v.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ---------------------------------------------------------------- 上半部分：待办 / 事实卡片

    private void setupCards() {
        mBinding.cards.setListener(new CardStackView.Listener() {
            @Override
            public void onCardClick(FactCard card) {
                FactsDialog.show(MainActivity.this, card.fact.userId, card.userName, false);
            }

            @Override
            public void onTopChanged(int index, int count) {
                mBinding.cardsIndex.setText(count <= 1 ? "" : getString(R.string.card_index, index + 1, count));
            }
        });
        mBinding.cardKind.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                mCardKind = checkedId == R.id.kind_fact ? UserFact.KIND_FACT : UserFact.KIND_TODO;
                renderCards();
            }
        });
        AppDatabase.get(this).userFactDao().observePendingCards().observe(this, cards -> {
            mPendingCards = cards;
            renderCards();
        });
    }

    private void renderCards() {
        List<FactCard> shown = new ArrayList<>();
        int todos = 0;
        int facts = 0;
        for (FactCard card : mPendingCards) {
            if (card.fact.kind == UserFact.KIND_FACT) {
                facts++;
            } else {
                todos++;
            }
            if (card.fact.kind == mCardKind) {
                shown.add(card);
            }
        }
        mBinding.kindTodo.setText(todos > 0
                ? getString(R.string.kind_todo_count, todos) : getString(R.string.kind_todo));
        mBinding.kindFact.setText(facts > 0
                ? getString(R.string.kind_fact_count, facts) : getString(R.string.kind_fact));
        mBinding.cards.submit(shown);
        mBinding.cardsEmpty.setText(mCardKind == UserFact.KIND_FACT
                ? R.string.cards_empty_facts : R.string.cards_empty);
        mBinding.cardsEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 下半部分：用户与实时转写

    private void setupUsers() {
        mUserAdapter = new UserChipAdapter(new UserChipAdapter.Listener() {
            @Override
            public void onUserClick(UserDao.UserSummary user) {
                if (user.isStranger) {
                    startActivity(SpeakerEditActivity.createIntent(MainActivity.this, user.id));
                } else {
                    FactsDialog.show(MainActivity.this, user.id, user.name, false);
                }
            }

            @Override
            public void onUserLongClick(UserDao.UserSummary user) {
                startActivity(UserDetailActivity.createIntent(MainActivity.this, user.id));
            }
        });
        mBinding.users.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.users.setAdapter(mUserAdapter);
        AppDatabase.get(this).userDao().observeSummaries().observe(this, users -> {
            // 只有有待办或事实的人才显示标签；mUsers 仍保留所有人，点转写里陌生人的发言要靠它打开命名窗口。
            mUsers.clear();
            List<UserDao.UserSummary> chips = new ArrayList<>();
            for (UserDao.UserSummary u : users) {
                mUsers.put(u.id, u);
                if (u.pendingTodos > 0 || u.pendingFacts > 0) {
                    chips.add(u);
                }
            }
            mUserAdapter.submitList(chips);
        });
    }

    private void onSpeakerClick(TranscriptLine line) {
        if (line.userId == TranscriptLine.NO_USER) {
            startActivity(SpeakerEditActivity.createAssignIntent(this, line));
            return;
        }
        UserDao.UserSummary user = mUsers.get(line.userId);
        if (user == null) {
            return;
        }
        if (user.isStranger) {
            startActivity(SpeakerEditActivity.createIntent(this, user.id));
        } else {
            FactsDialog.show(this, user.id, user.name, false);
        }
    }

    /** 长按任意一句话：认错人了，改成别人说的（或新建一个人）。 */
    private void onSpeakerLongClick(TranscriptLine line) {
        startActivity(SpeakerEditActivity.createAssignIntent(this, line));
    }

    private void setupTranscript() {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mBinding.transcript.setLayoutManager(lm);
        mTranscriptAdapter = new TranscriptAdapter(this::onSpeakerClick, this::onSpeakerLongClick);
        mBinding.transcript.setAdapter(mTranscriptAdapter);
        ServiceState.lines().observe(this, lines -> {
            boolean atBottom = !mBinding.transcript.canScrollVertically(1);
            mTranscriptAdapter.submitList(lines, () -> {
                if (atBottom && !lines.isEmpty()) {
                    mBinding.transcript.scrollToPosition(lines.size() - 1);
                }
            });
            mBinding.empty.setVisibility(lines.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    // ---------------------------------------------------------------- 录音控制

    private boolean hasRecordPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionsAndStart() {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Even G2 眼镜用 BLE；只在开关打开时顺带申请，拒绝了也不影响录音（配对页里可以再授权）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Settings.isGlassesPushEnabled(this)) {
            wanted.add(Manifest.permission.BLUETOOTH_SCAN);
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            startRecordingService();
        } else {
            mPermissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (hasRecordPermission()) {
            startRecordingService();
        } else {
            showPermissionDialog();
        }
        // 蓝牙权限刚批下来的话，让眼镜连接按当前设置重新走一遍（之前可能因缺权限停在"未连接"）。
        GlassesManager.applyEnabled(this);
    }

    /** 录音权限被拒：说明原因，并提供去系统设置的入口，而不是只弹一个 Toast。 */
    private void showPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_rationale)
                .setPositiveButton(R.string.permission_settings, (d, w) -> startActivity(
                        new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void startRecordingService() {
        startForegroundService(new Intent(this, RecordingService.class));
    }

    private void toggleRecording() {
        String action = mStatus == ServiceState.Status.RECORDING
                ? RecordingService.ACTION_PAUSE : RecordingService.ACTION_RESUME;
        startForegroundService(new Intent(this, RecordingService.class).setAction(action));
    }

    private void renderStatus() {
        String text;
        int dot;
        switch (mStatus) {
            case PREPARING:
                text = getString(R.string.status_preparing);
                dot = getColor(R.color.state_warn);
                break;
            case RECORDING:
                text = getString(R.string.status_recording);
                dot = getColor(R.color.state_danger);
                break;
            case ERROR:
                text = getString(R.string.status_error, ServiceState.error().getValue());
                dot = getColor(R.color.state_danger);
                break;
            default:
                text = getString(R.string.status_paused);
                dot = getColor(R.color.state_idle);
                break;
        }
        mBinding.status.setText(text);
        mBinding.statusDot.setBackgroundTintList(ColorStateList.valueOf(dot));
        boolean recording = mStatus == ServiceState.Status.RECORDING;
        mBinding.toggle.setEnabled(recording
                || mStatus == ServiceState.Status.PAUSED
                || mStatus == ServiceState.Status.ERROR);
        mBinding.toggle.setText(recording ? R.string.action_pause : R.string.action_resume);
        mBinding.toggle.setIconResource(recording ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** 出错时状态栏只放得下两行，点一下看完整原因和处理办法。 */
    private void showStatusDetail() {
        if (mStatus != ServiceState.Status.ERROR) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_error_title)
                .setMessage(getString(R.string.status_error_detail, ServiceState.error().getValue()))
                .setPositiveButton(R.string.action_resume, (d, w) -> startForegroundService(
                        new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_RESUME)))
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    // ---------------------------------------------------------------- AI 状态

    private void renderAiStatus(ServiceState.AiStatus s) {
        if (s == null) {
            return;
        }
        mAiStatus = s;
        String text;
        int dot;
        switch (s.state) {
            case WORKING:
                text = getString(R.string.ai_working, s.pending);
                dot = getColor(R.color.state_busy);
                break;
            case RETRYING:
                text = getString(R.string.ai_retrying, s.detail, s.pending);
                dot = getColor(R.color.state_warn);
                break;
            case OFFLINE:
                text = getString(R.string.ai_offline, s.pending);
                dot = getColor(R.color.state_idle);
                break;
            case ERROR:
                text = getString(R.string.ai_error, s.detail);
                dot = getColor(R.color.state_danger);
                break;
            default:
                text = getString(R.string.ai_idle);
                dot = getColor(R.color.state_ok);
                break;
        }
        mBinding.aiStatus.setText(text);
        mBinding.aiDot.setBackgroundTintList(ColorStateList.valueOf(dot));
    }

    /** 立即把所有还没交给 AI 的内容送去识别，并如实反馈：有新内容 / 只是在重连 / 没有新内容。 */
    private void extractAll() {
        if (RecordingService.getInstance() == null) {
            Toast.makeText(this, R.string.service_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        int pending = mAiStatus == null ? 0 : mAiStatus.pending;
        AppDatabase.IO.execute(() -> {
            int notExtracted = AppDatabase.get(this).utteranceDao().countNotExtracted();
            runOnUiThread(() -> {
                RecordingService service = RecordingService.getInstance();
                if (isFinishing() || service == null) {
                    return;
                }
                service.extractAllNotExtracted();
                int message = notExtracted > 0 ? R.string.extract_all_started
                        : pending > 0 ? R.string.extract_all_retrying : R.string.extract_all_none;
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmClearChats() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_clear_chats)
                .setMessage(R.string.clear_chats_confirm)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    RecordingService service = RecordingService.getInstance();
                    if (service != null) {
                        service.clearAllChats();
                    } else {
                        Toast.makeText(this, R.string.service_not_running, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- 「更多」底部面板

    private boolean hasOwner() {
        for (UserDao.UserSummary u : mUsers.values()) {
            if (u.isOwner) {
                return true;
            }
        }
        return false;
    }

    private void showMoreSheet() {
        if (mSheet != null && mSheet.isShowing()) {
            return;
        }
        SheetMoreBinding b = SheetMoreBinding.inflate(getLayoutInflater());
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(b.getRoot());
        // 一打开就完全展开，所有开关和操作一眼可见，不用再往上拖一次。
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        mSheet = sheet;

        // 开关：整行可点，和点开关本身效果一样。
        b.switchCaption.setChecked(Settings.isLiveCaptionEnabled(this));
        b.switchCaption.setOnCheckedChangeListener((v, checked) -> {
            Settings.setLiveCaptionEnabled(this, checked);
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                service.setLiveCaptionEnabled(checked);
            }
        });
        b.rowCaption.setOnClickListener(v -> b.switchCaption.toggle());

        b.switchScreenshot.setChecked(Settings.isScreenshotWatchEnabled(this));
        b.switchScreenshot.setOnCheckedChangeListener((v, checked) -> {
            Settings.setScreenshotWatchEnabled(this, checked);
            RecordingService service = RecordingService.getInstance();
            if (service != null) {
                service.setScreenshotWatchEnabled(checked);
            }
        });
        b.rowScreenshot.setOnClickListener(v -> b.switchScreenshot.toggle());

        b.thresholdValue.setText(getString(R.string.threshold_value, Settings.getSpeakerThreshold(this)));
        b.rowThreshold.setOnClickListener(v -> {
            sheet.dismiss();
            showThresholdDialog();
        });

        String glassesName = Settings.getGlassesLeftName(this);
        b.glassesValue.setText(Settings.isGlassesPushEnabled(this)
                ? getString(R.string.glasses_row_on, glassesName != null ? glassesName
                        : getString(R.string.glasses_remembered_none))
                : getString(R.string.glasses_row_off));
        b.rowGlasses.setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(new Intent(this, GlassesPairingActivity.class));
        });

        b.rowExtract.setOnClickListener(v -> {
            sheet.dismiss();
            extractAll();
        });
        b.rowUsers.setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(new Intent(this, UserListActivity.class));
        });

        boolean hasOwner = hasOwner();
        b.enrollTitle.setText(hasOwner ? R.string.menu_reenroll : R.string.enroll_title);
        b.enrollValue.setText(hasOwner ? R.string.menu_reenroll_hint : R.string.enroll_title_hint);
        b.rowEnroll.setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(VoiceEnrollActivity.createOwnerIntent(this, hasOwner));
        });

        b.rowClear.setOnClickListener(v -> {
            sheet.dismiss();
            confirmClearChats();
        });

        sheet.setOnDismissListener(d -> mSheet = null);
        sheet.show();
    }

    private void showThresholdDialog() {
        float current = Settings.getSpeakerThreshold(this);
        DialogThresholdBinding b = DialogThresholdBinding.inflate(getLayoutInflater());
        b.slider.setValue(Math.max(0.3f, Math.min(0.8f, Math.round(current * 20) / 20f)));
        b.value.setText(getString(R.string.threshold_value, b.slider.getValue()));
        b.slider.addOnChangeListener((slider, value, fromUser) ->
                b.value.setText(getString(R.string.threshold_value, value)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_threshold)
                .setView(b.getRoot())
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    float value = b.slider.getValue();
                    Settings.setSpeakerThreshold(this, value);
                    RecordingService service = RecordingService.getInstance();
                    if (service != null) {
                        service.setSpeakerThreshold(value);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
