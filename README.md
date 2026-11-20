# Hannun WebView for Android

한눈 서비스가 공통으로 사용하는 **독립 Android library**입니다. 앱 전체 프로젝트를 복제하지 않고, 두 앱이 이 소스 모듈의 고정된 Git commit을 submodule로 참조합니다. 앱별 HTML, 패키지, 광고 ID, 서명은 소비 앱에 있습니다.

## 제공 기능

| 영역 | 구현 |
|---|---|
| HTML 실행 | WebViewAssetLoader, `https://appassets.androidplatform.net/assets/index.html` |
| 네이티브 메시지 | WebViewCompat.addWebMessageListener, HTTPS origin + main frame + 현재 document 검사 |
| 백업 저장 | ACTION_CREATE_DOCUMENT, JSON/text MIME, 안전한 basename, UTF-8 최대 5 MiB |
| 백업 불러오기 | ACTION_OPEN_DOCUMENT, content URI만 전달, 알려진 크기 5 MiB 제한 |
| 인쇄 | Android PrintManager + WebView PrintDocumentAdapter, 인쇄창 종료 callback |
| 공유 | 앱의 공개 HTTPS 경로로 제한한 ACTION_SEND |
| 외부 링크 | HTTPS Custom Tabs, 실패 시 HTTPS ACTION_VIEW |
| 네트워크 | WebView에서 로컬 assets 외 요청 차단, SSL 오류 취소 |
| 광고 공존 | AdMobNative/1 UA, AdFit 요청과 빈 컨테이너 제거 |
| 생명주기 | WebView pause/resume/destroy, SAF 취소, 시스템·키보드 inset 처리 |

AdMob과 Play 업데이트는 이 라이브러리에 복제하지 않습니다. 앱에서 각각 기존 공통 AAR을 의존하고 제공된 hooks에 연결합니다. 공통계정은 외부 브라우저에서 열리며 로컬 기록과 자동 동기화하지 않습니다.

## 소비 앱 연결

```kotlin
// App settings.gradle.kts
include(":hannun-webview")
project(":hannun-webview").projectDir =
    file("libraries/hannun-webview-android/library")

// App app/build.gradle.kts
implementation(project(":hannun-webview"))
implementation("com.github.softm:hannun-admob-android:1.0.4")
implementation("com.github.softm:hannun-in-app-update-android:1.0.0")
```

Activity는 `HannunWebViewActivity`를 상속하고 `webConfig()`에서 앱 이름/공개 URL/Debug 여부를 제공합니다. `onWebViewCreated(WebView, LinearLayout)`에서 공통 광고·업데이트 manager를 생성합니다. 업데이트/개인정보/종료 메뉴는 각각의 protected hook으로 전달됩니다.

## 웹 메시지 계약

```javascript
HannunNative.postMessage(JSON.stringify({
  type: "downloadText",
  name: "백업.json",
  mime: "application/json",
  text: JSON.stringify(backup)
}));
HannunNative.postMessage(JSON.stringify({ type: "print" }));
HannunNative.postMessage(JSON.stringify({
  type: "share", title: "서비스", text: "소개", url: "https://example.com/app/"
}));
HannunNative.onmessage = function (event) {
  const result = JSON.parse(event.data);
  // downloadResult: ok, cancelled, message
  // printResult: 인쇄 화면이 닫혔다는 알림. 실제 프린터 출력 성공 보장이 아님.
};
```

원시 메시지는 12 MiB 문자 수 이내, 실제 파일 본문은 UTF-8 5 MiB 이내입니다. JSON escape의 증가를 고려해 한도를 분리합니다. 파일 본문은 그대로 보존하므로 손상된 백업의 원본 복구도 가능합니다. 메시지 bridge를 지원하지 않는 오래된 Android System WebView에서는 네이티브 내보내기 기능 사용을 위해 WebView 업데이트가 필요합니다.

## 빌드와 검증

```sh
./gradlew :library:testDebugUnitTest :library:assembleDebug
```

- JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13.
- 실제 Gradle 8.13 wrapper JAR과 배포 ZIP checksum을 고정했습니다.
- 단위 테스트는 origin 우회, 경로 이탈, MIME/파일명, Unicode UTF-8 용량, AdFit 구분, 공유 경로를 검증합니다.
- 파일 선택/저장, 인쇄창, 외부 브라우저, UMP 광고, Google Play 업데이트는 실제 Android 기기에서 확인해야 합니다.
- SAF 파일 선택 중 프로세스가 종료되면 미완료 저장을 다시 시작해야 합니다. 일반 회전은 소비 앱의 configChanges로 Activity를 유지합니다.

## 근거

- [AGP 8.13.2 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes?hl=en)
- [WebViewCompat 메시지 API](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener))
- [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- [Gradle 공식 checksum](https://gradle.org/release-checksums/)
- Wrapper source: [gradle/gradle v8.13.0](https://github.com/gradle/gradle/tree/v8.13.0/gradle/wrapper)
