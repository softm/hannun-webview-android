# 한눈 Android WebView 공통 라이브러리

- 이 저장소는 앱 전체 템플릿이 아니라 반복 WebView 기능만 제공하는 독립 Android library이다.
- 앱 이름, applicationId, 웹 콘텐츠, 공개 URL, 운영 AdMob ID와 서명은 소비 앱에서 관리한다.
- AdMob 및 Play 업데이트 구현을 복제하지 않는다. 소비 앱이 hannun-admob-android와 hannun-in-app-update-android를 별도로 사용한다.
- Java 17, compileSdk 36, minSdk 24를 유지한다.
- Native bridge는 appassets HTTPS origin과 main frame으로 제한한다. addJavascriptInterface나 wildcard origin을 추가하지 않는다.
- 외부 링크는 HTTPS 브라우저에서 연다. SSL 오류를 무시하거나 cleartext/file URL로 전환하지 않는다.
- 다운로드는 사용자가 선택한 SAF 문서에만 저장한다. MIME/파일명/UTF-8 5 MiB 제한을 유지한다.
- 원본 복구 내보내기는 손상된 JSON도 보존해야 하므로 파일 본문을 JSON 유효성 검사로 거부하지 않는다.
- 운영 키를 새로 만들지 않는다. 소비 앱은 기존 softm.keystore.jks만 사용하며 키/자격증명은 커밋하지 않는다.
- 경계 검증 테스트와 Android library assembly를 통과한 소스 commit만 소비 앱에서 고정한다.
