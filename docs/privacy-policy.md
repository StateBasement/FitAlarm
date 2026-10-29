# 운동 알람 개인정보처리방침

시행일: 2026년 10월 29일

Dev_light(이하 "개발자")은 안드로이드 앱 「운동 알람」(이하 "앱")을 제공하면서 「개인정보 보호법」 등 관련 법령을 지키고, 이용자의 개인정보가 어떻게 다뤄지는지 다음과 같이 알려드립니다.

## 1. 요약

- 앱에는 **회원가입·로그인이 없고**, 개발자는 **별도 서버를 운영하지 않습니다.**
- **개발자는 이용자의 개인정보를 수집하거나 저장하지 않습니다.** 광고와 관련해 개발자가 받는 것은 광고 노출·클릭 수 같은 집계 보고서뿐이며, 이용자 개인을 알아볼 수 있는 정보는 받지 않습니다.
- 카메라 영상, 달력 일정, 알람 설정, 운동 기록은 **이용자의 기기 안에서만 처리**되며 개발자나 광고 회사에 전송되지 않습니다.
- 다만 앱에 포함된 Google의 두 기능이 일부 정보를 **Google로 직접 전송**합니다(아래 4항).
  - **ML Kit**(운동 동작 인식): 기능 진단과 사용 통계를 위한 기기 정보 등
  - **AdMob**(광고): 광고 표시를 위한 광고 ID, IP 주소, 앱·광고 이용 정보 등

## 2. 기기 안에서만 처리되는 정보

| 정보 | 처리 내용 | 저장 위치·기간 |
|---|---|---|
| 카메라 영상 | 운동 미션 중 화면에 비친 사람의 관절 위치(좌표)를 실시간으로 계산해 운동 횟수를 셉니다. 영상과 사진은 저장하거나 전송하지 않으며, 계산이 끝난 프레임은 즉시 버려집니다. | 저장하지 않음 |
| 달력(캘린더) | 이용자가 허용한 경우, 폰 달력 중 **공휴일 달력**의 일정 제목과 날짜만 읽어 쉬는 날을 판단합니다. 개인 일정이 들어 있는 달력은 읽지 않습니다. | 공휴일 날짜·이름만 앱 내부 저장소에 저장 |
| 알람 설정, 내 휴무일, 운동 기록, 개인 보정 값 | 알람 시각·반복·알람음 설정, 등록한 휴무일, 날짜별 운동 횟수와 연속 기록, 동작 인식 기준값을 저장합니다. | 앱 내부 저장소, 앱 삭제 또는 앱 데이터 삭제 시 함께 삭제. 구글 클라우드 백업에는 올리지 않으며, 이용자가 새 휴대폰으로 직접 옮길 때(기기 간 이전)만 함께 옮겨짐 |
| 알람음으로 고른 음악 파일 | 이용자가 시스템 파일 선택 화면에서 직접 고른 파일 하나에 대해서만 재생 권한을 받습니다. | 파일 위치(주소)만 저장 |
| 백업 파일 | 이용자가 "백업 내보내기"를 누르면 위 설정과 기록을 이용자가 고른 위치에 파일로 저장합니다. 이 파일은 암호화되지 않으므로 이용자가 안전하게 보관해야 합니다. | 이용자가 지정한 위치 |
| 음성 안내 | 알람 시 날짜·시각·운동 이름을 폰에 설치된 음성 엔진(삼성·구글 등)으로 읽습니다. 앱은 읽을 문장만 엔진에 전달합니다. | 저장하지 않음 |

## 3. 앱이 인터넷에 접속하는 경우

- **공휴일 목록 업데이트**: 하루 한 번 Google 캘린더의 공개 "대한민국의 휴일" 달력을 내려받아 임시공휴일·선거일을 반영합니다. 이 요청에는 이용자의 개인정보가 담기지 않지만, 인터넷 통신의 특성상 접속 IP 주소 등 일반적인 접속 정보가 Google에 전달될 수 있습니다. 설정 > 공휴일 > "공휴일 자동 업데이트"를 끄면 접속하지 않습니다.
- **광고**: 앱의 기본 화면(알람·기록·설정 탭) 맨 아래에 표시할 광고를 Google AdMob에서 불러옵니다(아래 4-2항). 잠금 화면 위에 뜨는 알람·운동 미션 화면에는 광고를 표시하지 않습니다.
- **ML Kit**: 아래 4-1항.

## 4. 외부 서비스(SDK)로 전송되는 정보 및 국외 이전

앱에는 Google LLC의 아래 기능이 포함되어 있으며, 이 기능들은 다음 정보를 개발자를 거치지 않고 Google로 직접 전송합니다.
카메라 영상, 운동 기록, 달력 일정, 알람 설정, 이용자의 이름·연락처는 어느 기능에도 포함되지 않습니다.

### 4-1. ML Kit (운동 동작 인식)

동작 인식 모델은 앱에 포함되어 기기 안에서 동작합니다. Google의 ML Kit 데이터 공개 문서에 따르면 ML Kit는 다음 정보를 Google로 전송합니다.

| 항목 | 내용 |
|---|---|
| 이전받는 자 | Google LLC (ML Kit 및 Firebase 설치·원격 구성 서비스) |
| 이전되는 국가 | 미국 등 Google이 서버를 운영하는 국가 |
| 이전 항목 | 기기 정보(제조사, 모델, OS 버전, 사용 가능한 ML 하드웨어 가속기), 앱 정보(패키지 이름, 앱 버전), 설치별 식별자(이용자나 실제 기기를 고유하게 식별하지 않음), 성능 지표(처리 시간 등), 기능 설정·입출력 크기·기능 버전, 이벤트 유형, 오류 코드 |
| 이전 목적 | ML Kit 기능의 문제 진단 및 사용 현황 분석, 기능 구성 |
| 이전 시기·방법 | 앱에서 ML Kit가 동작할 때 인터넷을 통해 수시로, HTTPS로 암호화해 전송 |
| 보유·이용 기간 | Google의 정책에 따름 ([Google 개인정보처리방침](https://policies.google.com/privacy)) |
| 거부 방법 | 운동 미션을 사용하지 않거나 기기의 인터넷 연결을 끄면 전송을 줄이거나 막을 수 있음. 거부해도 일반 알람은 그대로 사용 가능 |

ML Kit는 위 정보를 서드 파티에 제공하지 않는다고 밝히고 있습니다. 참고: [ML Kit 데이터 공개 안내](https://developers.google.com/ml-kit/android-data-disclosure?hl=ko)

### 4-2. Google AdMob (광고) 및 광고 동의 도구

앱은 무료로 제공하기 위해 Google AdMob 광고를 표시합니다. Google의 AdMob 데이터 공개 문서에 따르면 AdMob은 다음 정보를 자동으로 수집해 Google에 전송합니다.
유럽·영국 등 법에 따라 광고 동의가 필요한 지역의 이용자에게는 Google의 광고 동의 도구(User Messaging Platform)가 동의 화면을 보여주고, 이용자의 선택을 Google에 전달합니다.

| 항목 | 내용 |
|---|---|
| 이전받는 자 | Google LLC (Google AdMob, 광고 동의 도구) |
| 이전되는 국가 | 미국 등 Google이 서버를 운영하는 국가 |
| 이전 항목 | 기기 식별자(Android 광고 ID, 앱 세트 ID, 해당하는 경우 기기에 로그인한 계정과 관련된 식별자), IP 주소(기기의 대략적인 위치를 추정하는 데 쓰일 수 있음), 앱 실행·광고 조회·탭 등 앱과 광고의 이용 정보, 앱 실행 시간 등 앱과 광고 SDK의 성능 정보(진단 정보), 동의가 필요한 지역에서의 광고 동의 선택 |
| 이전 목적 | 광고 표시와 광고 효과 측정, 이용자 관심에 맞춘 광고(맞춤형 광고), 분석, 부정 클릭 등 사기 방지 |
| 이전 시기·방법 | 앱의 기본 화면을 열어 광고를 불러올 때 수시로, 인터넷을 통해 TLS(HTTPS)로 암호화해 전송 |
| 보유·이용 기간 | Google의 정책에 따름 ([Google 개인정보처리방침](https://policies.google.com/privacy), [Google이 파트너 앱에서 수집한 정보를 사용하는 방식](https://policies.google.com/technologies/partner-sites?hl=ko)) |
| 거부 방법 및 거부 시 영향 | 아래 5항의 방법으로 광고 ID를 삭제·재설정하거나 맞춤 광고를 끌 수 있으며, 이 경우에도 광고는 표시되지만 관심사에 맞춘 광고가 아닌 일반 광고가 표시됩니다. 기기의 인터넷 연결을 끄면 광고 요청 자체가 이루어지지 않으며, 알람 등 앱의 기능은 그대로 사용할 수 있습니다. |

참고: [AdMob의 Google Play 데이터 공개 안내](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure?hl=ko)

## 5. 행태정보의 수집·이용 및 거부 (맞춤형 광고)

앱은 Google이 다음과 같이 맞춤형 광고를 위한 행태정보를 수집·처리하도록 허용하고 있습니다.

| 항목 | 내용 |
|---|---|
| 수집하는 행태정보 | 앱 실행, 광고 조회·클릭 등 앱과 광고의 이용 기록, 광고 ID |
| 수집 방법 | 앱에 포함된 Google AdMob이 광고를 불러올 때 자동으로 수집 |
| 수집 목적 | 이용자의 관심과 성향에 맞춘 광고 제공, 광고 효과 측정 |
| 보유·이용 기간 | Google의 정책에 따름 |
| 행태정보를 수집·처리하는 사업자 | Google LLC |
| 이용자의 통제 방법 | ① 휴대폰 설정 > Google > 광고에서 **광고 ID 삭제** 또는 **광고 ID 재설정** (기기에 따라 설정 > 개인정보 보호 > 광고 등 메뉴 위치가 다를 수 있음) ② Google 계정을 쓰는 경우 [내 광고 센터](https://myadcenter.google.com)에서 맞춤 광고 끄기 ③ 유럽 등 동의가 필요한 지역에서는 앱의 설정 > 정보 > "광고 개인정보 설정"에서 동의를 바꿀 수 있음 |

- 운동 기록, 카메라 영상 등 건강과 관련된 정보는 광고에 사용하지 않으며 광고 요청에 포함되지 않습니다.
- 앱은 만 14세 미만 아동을 대상으로 하지 않으며, 아동임을 알면서 맞춤형 광고를 위한 행태정보를 수집하도록 허용하지 않습니다.
- 행태정보와 관련한 문의나 피해 구제 요청은 아래 11항의 연락처로 보내 주세요.

## 6. 개인정보의 제3자 제공

개발자는 이용자의 개인정보를 수집하지 않으므로 제3자에게 제공하지 않습니다. 앱에 포함된 Google의 기능(4항)이 정보를 Google에 직접 전송하는 것 외에 다른 광고·분석 SDK는 사용하지 않습니다.

## 7. 앱이 요청하는 권한

| 권한 | 용도 | 필수 여부 |
|---|---|---|
| 카메라 | 운동 미션의 동작 인식 | 운동 미션 사용 시 필요 (거부해도 일반 알람은 사용 가능) |
| 캘린더 읽기 | 폰 달력의 공휴일 달력 확인 | 선택 (거부해도 앱 내장 규칙과 공휴일 업데이트로 동작) |
| 알림 | 알람 화면·다시 알림·놓친 알람 안내 | 알람 표시에 필요 |
| 정확한 알람, 전체 화면 알림 | 정해진 시각에 잠금 화면 위로 알람 화면 표시 | 알람 앱 핵심 기능 |
| 진동, 화면 켜짐 유지, 포그라운드 서비스 | 알람이 울리는 동안 소리·진동 재생 | 알람 앱 핵심 기능 |
| 부팅 완료 수신 | 재부팅 후 알람 다시 등록 | 알람 앱 핵심 기능 |
| 인터넷, 네트워크 상태 | 공휴일 목록 업데이트, 광고, ML Kit | 자동 허용 |
| 광고 ID | 광고 표시와 측정 (4-2항) | 자동 허용. 휴대폰 설정에서 광고 ID를 삭제하면 앱에 전달되지 않음 |
| 기본 전화 상태 읽기 | 광고 SDK가 자동으로 추가하는 일반 권한으로, 통신망 종류 등 기본 상태만 확인 (전화번호·통화 기록에는 접근할 수 없음) | 자동 허용 |

## 8. 개인정보의 파기

앱이 기기 안에 저장한 정보는 앱을 삭제하거나, 휴대폰 설정 > 애플리케이션 > 운동 알람 > 저장공간 > 데이터 삭제를 하면 즉시 모두 삭제됩니다. 이용자가 내보낸 백업 파일은 이용자가 직접 삭제할 수 있습니다. Google로 전송된 정보(4항)는 Google의 정책에 따라 보관·파기됩니다.

## 9. 이용자의 권리

이용자는 언제든지 앱 안에서 알람·휴무일을 삭제하거나 개인 보정을 초기화할 수 있고, 위 8항의 방법으로 기기 안의 모든 데이터를 지울 수 있습니다. 광고 ID와 맞춤형 광고는 5항의 방법으로 통제할 수 있으며, 권한은 휴대폰 설정에서 언제든 철회할 수 있습니다. 개인정보와 관련한 문의는 아래 연락처로 보내 주세요.

## 10. 아동의 개인정보

앱은 만 14세 미만 아동을 대상으로 하지 않으며, 아동의 개인정보를 알면서 수집하지 않습니다.

## 11. 안전성 확보 조치

앱의 데이터는 다른 앱이 접근할 수 없는 앱 전용 저장 공간에 저장되고, 구글 클라우드 백업에서 제외됩니다. 앱의 모든 인터넷 통신은 HTTPS로 암호화되며 암호화되지 않은 통신은 차단됩니다. 출시용 앱은 코드 난독화를 적용하고, 배포에 쓰는 서명 키는 저장소와 분리해 보관합니다.

## 12. 개인정보 보호책임자 및 문의

- 개인정보 보호책임자: Dev_light
- 이메일: kwangge95@gmail.com

개인정보 침해에 대한 신고나 상담은 개인정보침해신고센터(privacy.kisa.or.kr, 국번 없이 118), 개인정보분쟁조정위원회(www.kopico.go.kr, 1833-6972)에 문의할 수 있습니다.

## 13. 처리방침의 변경

이 방침이 바뀌면 시행일과 함께 이 페이지에 공지합니다.

---

# FitAlarm Privacy Policy (English summary)

Effective date: October 29, 2026

- FitAlarm has no accounts and the developer runs no servers. **The developer does not collect or store your personal data.** For ads, the developer only receives aggregated reports (such as impression and click counts) that do not identify you.
- Camera frames are processed **on your device only** to count exercise repetitions; images are never stored or transmitted.
- If you allow calendar access, the app reads only **holiday calendars** on your device; personal calendars are not read.
- Alarms, days off, workout records and calibration values are stored only in the app's private storage, are excluded from Google cloud backup (moved only during a direct device-to-device transfer you start), and are deleted when you uninstall the app or clear its data.
- Once a day the app downloads Google's public "Holidays in South Korea" calendar to pick up newly designated holidays. No personal data is sent (your IP address is visible to Google as with any internet request). You can turn this off in Settings.
- The app uses **Google ML Kit** for on-device pose detection. According to Google's ML Kit data disclosure, ML Kit sends device information (manufacturer, model, OS version, available ML accelerators), app information (package name, app version), a per-installation identifier that does not uniquely identify a user or physical device, performance metrics, API configuration, feature version, event types and error codes to Google over HTTPS, for diagnostics and usage analytics. See Google's [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- The app shows **Google AdMob** banner ads at the bottom of the main tabs (never on the alarm or exercise screens shown over the lock screen). According to Google's [AdMob data disclosure](https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure), AdMob automatically collects and shares with Google device and account identifiers (Android advertising ID, app set ID), IP address (which may be used to estimate approximate location), app and ad interactions (app launches, taps, video views) and diagnostic information, encrypted in transit, for advertising, analytics and fraud prevention, including personalized ads. Users in regions that require consent (such as the EEA and UK) see Google's consent form and can change their choice under Settings > Info > Ad privacy settings.
- You can reset or delete your advertising ID in your phone's settings (Settings > Google > Ads) and turn off personalized ads at [My Ad Center](https://myadcenter.google.com). Workout records, camera images, calendar data and alarm settings are never sent to ad networks. See [Google's Privacy Policy](https://policies.google.com/privacy) and [how Google uses information from apps that use its services](https://policies.google.com/technologies/partner-sites).
- No other advertising or analytics SDKs are used. The app is not directed at children under 14.
- Contact: Dev_light, kwangge95@gmail.com
