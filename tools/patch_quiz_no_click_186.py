"""Run after the complete 1.8.5 build patches."""
from pathlib import Path
import re
ROOT=Path('app/src/main/java/com/ruisi/changanmatch')
def remove_block(text,marker):
    start=text.index(marker); brace=text.index('{',start);depth=0
    token=re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|[{}]',re.S)
    for m in token.finditer(text,brace):
        if m[0]=='{':depth+=1
        elif m[0]=='}':
            depth-=1
            if depth==0:return text[:start]+text[m.end():]
    raise RuntimeError('Unbalanced block: '+marker)
def main():
    p=ROOT/'QuizActivity.java';s=p.read_text()
    s=s.replace('    private CheckBox autoClick;\n','')
    for start,end in [('        Button accessibilityButton =','        centerOnly ='),('        autoClick = new CheckBox(this);','        Button startButton =')]:
        a=s.index(start);b=s.index(end,a);s=s[:a]+s[b:]
    s=remove_block(s,'        if (autoClick.isChecked() && !AutomationAccessibilityService.isReady()) {')
    a=s.index('    private void updatePermissionState() {');b=s.index('    private void openOverlaySettings()',a)
    s=s[:a]+'''    private void updatePermissionState() {
        if (permissionState == null) return;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this);
        permissionState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权"));
        permissionState.setTextColor(overlay ? GREEN : Color.rgb(190, 80, 35));
    }

'''+s[b:]
    s=re.sub(r'^.*service.putExtra\(QuizScreenCaptureService.EXTRA_AUTO_CLICK.*\n','',s,flags=re.M)
    s=s.replace('autoClick.isChecked() ? "识题与自动点击已启动，请切回游戏" : "识题已启动，请切回游戏"','"识题已启动，请手动选择答案"')
    s=s.replace('屏幕识题与自动点击','屏幕识题·手动答题').replace('③ 开始屏幕识题','② 开始屏幕识题').replace('停止识题与自动点击','停止识题')
    s=s.replace('启动后切回游戏，助手会自动识别题目并显示答案。开启自动点击时会点击正确选项；关闭自动点击后为手动模式，答错时会识别游戏公布的正确选项并保存到本机学习题库。','启动后切回游戏，助手识别题目并显示答案，请手动点击选项。答错时会识别游戏公布的正确选项并保存到本机学习题库。')
    s=s.replace('自动点击只会在题库匹配成功且屏幕中识别到答案文字时执行；匹配不确定或找不到答案选项时不会点击。','仅提示版：请自行点击答案和翻页，无需开启无障碍服务。')
    s=s.replace('关闭自动点击后，答错页面','答错页面');p.write_text(s)
    p=ROOT/'MainActivity.java';text=p.read_text()
    text=text.replace('支持悬浮显示答案和自动点击。手动点击模式下，','支持悬浮显示答案，请手动点击选项。')
    text=text.replace('屏幕识别、悬浮窗及无障碍自动点击功能','屏幕识别与悬浮窗提示功能')
    p.write_text(text)
    p=ROOT/'QuizScreenCaptureService.java';s=p.read_text()
    for marker in ['    private boolean tryAutoPageAction(','    private void tryAutoClickAnswer(','    private int findAnswerOptionIndex(','    private AnswerTarget findAnswerTarget(','    private double answerLineScore(','    private static final class AnswerTarget {']:
        s=remove_block(s,marker)
    for pattern in [r'^.*public static final String EXTRA_AUTO_CLICK.*\n',r'^.*private boolean autoClick;.*\n',r'^.*autoClick = intent.getBooleanExtra.*\n']:
        s=re.sub(pattern,'',s,flags=re.M)
    s=s.replace('        if (autoClick) tryAutoClickAnswer(result, match);\n','').replace('!autoClick && ','')
    s=re.sub(r'autoClick\s*\?\s*"(?:\\.|[^"\\])*"\s*:\s*("(?:\\.|[^"\\])*")',r'\1',s);p.write_text(s)
    # Keep only the compile-time interface used by the inactive match-3 sources.
    (ROOT/'AutomationAccessibilityService.java').write_text('''package com.ruisi.changanmatch;
final class AutomationAccessibilityService {
    private AutomationAccessibilityService() { }
    static boolean isReady() { return false; }
    static boolean performSwipe(float startX, float startY, float endX, float endY) {
        return false;
    }
}
''')
    p=Path('app/src/main/AndroidManifest.xml');s=p.read_text()
    s,n=re.subn(r'\s*<service\s+android:name="\.AutomationAccessibilityService".*?</service>','',s,flags=re.S)
    if n!=1:raise RuntimeError('Accessibility registration not found')
    p.write_text(s)
    Path('app/src/main/res/xml/accessibility_service_config.xml').unlink(missing_ok=True)
    p=Path('app/src/main/res/values/strings.xml');s=p.read_text()
    s=re.sub(r'\s*<string name="accessibility_[^"]*">.*?</string>','',s,flags=re.S)
    s=re.sub(r'(<string name="app_name">).*?(</string>)',r'\1深情助手·仅提示版\2',s);p.write_text(s)
    # Upstream patches are materialized; prevent preBuild mutators reintroducing them.
    p=Path('app/build.gradle');s=p.read_text().split('// 在编译前')[0]
    s=re.sub(r'versionCode\s+\d+','versionCode 22',s,count=1)
    s=re.sub(r'versionName\s+"[^"]+"','versionName "1.8.6-no-click"',s,count=1);p.write_text(s)
    for name in ['QuizActivity.java','QuizScreenCaptureService.java']:
        if re.search(r'autoClick|EXTRA_AUTO_CLICK|performTap|tryAuto|ACTION_ACCESSIBILITY_SETTINGS|自动点击',(ROOT/name).read_text()):
            raise RuntimeError('Automatic-click code remains: '+name)
    for p in ROOT.glob('*.java'):
        if re.search(r'dispatchGesture|GestureDescription|extends AccessibilityService|performTap\(',p.read_text()):
            raise RuntimeError('Gesture implementation remains: '+str(p))
    if 'BIND_ACCESSIBILITY_SERVICE' in Path('app/src/main/AndroidManifest.xml').read_text():
        raise RuntimeError('Accessibility permission remains')
    for name,markers in {'QuizActivity.java':['调整题目识别区域','查看错题学习记录','点击查看题库'],'QuizScreenCaptureService.java':['OCR_INTERVAL_MS = 280L','loadRecognitionRegion','tryLearnManualCorrection','updateQuizSession']}.items():
        for marker in markers:
            if marker not in (ROOT/name).read_text():raise RuntimeError('Retained feature missing: '+marker)
    print('1.8.6 manual patch verified: no gestures; ROI, learning and viewer retained')
if __name__=='__main__':main()
