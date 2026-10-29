# 운동 알람 개인정보처리방침

시행일: 2026년 10월 29일

[개발자 이름](이하 "개발자")은 안드로이드 앱 「운동 알람」(이하 "앱")을 제공하면서 「개인정보 보호법」 등 관련 법령을 지키고, 이용자의 개인정보가 어떻게 다뤄지는지 다음과 같이 알려드립니다.

## 1. 요약

- 앱에는 **회원가입·로그인이 없고**, 개발자는 **별도 서버를 운영하지 않습니다.**
- **개발자는 이용자의 개인정보를 수집하거나 저장하지 않습니다.**
- 카메라 영상, 달력 일정, 알람 설정, 운동 기록은 **이용자의 기기 안에서만 처리**되며 개발자에게 전송되지 않습니다.
- 다만 운동 동작 인식에 쓰는 Google의 **ML Kit**가 기능 진단과 사용 통계를 위해 기기 정보 등 일부 정보를 **Google로 전송**합니다(아래 4항).

## 2. 기기 안에서만 처리되는 정보

| 정보 | 처리 내용 | 저장 위치·기간 |
|---|---|---|
| 카메라 영상 | 운동 미션 중 화면에 비친 사람의 관절 위치(좌표)를 실시간으로 계산해 운동 횟수를 셉니다. 영상과 사진은 저장하거나 전송하지 않으며, 계산이 끝난 프레임은 즉시 버려집니다. | 저장하지 않음 |
| 달력(캘린더) | 이용자가 허용한 경우, 폰 달력 중 **공휴일 달력**의 일정 제목과 날짜만 읽어 쉬는 날을 판단합니다. 개인 일정이 들어 있는 달력은 읽지 않습니다. | 공휴일 날짜·이름만 앱 내부 저장소에 저장 |
| 알람 설정, 내 휴무일, 운동 기록, 개인 보정 값 | 알람 시각·반복·알람음 설정, 등록한 휴무일, 날짜별 운동 횟수와 연속 기록, 동작 인식 기준값을 저장합니다. | 앱 내부 저장소, 앱 삭제 또는 앱 데이터 삭제 시 함께 삭제 |
| 알람음으로 고른 음악 파일 | 이용자가 시스템 파일 선택 화면에서 직접 고른 파일 하나에 대해서만 재생 권한을 받습니다. | 파일 위치(주소)만 저장 |
| 백업 파일 | 이용자가 "백업 내보내기"를 누르면 위 설정과 기록을 이용자가 고른 위치에 파일로 저장합니다. | 이용자가 지정한 위치 |
| 음성 안내 | 알람 시 날짜·시각·운동 이름을 폰에 설치된 음성 엔진(삼성·구글 등)으로 읽습니다. 앱은 읽을 문장만 엔진에 전달합니다. | 저장하지 않음 |

## 3. 앱이 인터넷에 접속하는 경우

- **공휴일 목록 업데이트**: 하루 한 번 Google 캘린더의 공개 "대한민국의 휴일" 달력을 내려받아 임시공휴일·선거일을 반영합니다. 이 요청에는 이용자의 개인정보가 담기지 않지만, 인터넷 통신의 특성상 접속 IP 주소 등 일반적인 접속 정보가 Google에 전달될 수 있습니다. 설정 > 공휴일 > "공휴일 자동 업데이트"를 끄면 접속하지 않습니다.
- **ML Kit**: 아래 4항.

## 4. 외부 서비스(SDK)로 전송되는 정보 및 국외 이전

앱은 운동 동작 인식을 위해 Google의 ML Kit 포즈 인식 기능(인식 모델은 앱에 포함되어 기기 안에서 동작)을 사용합니다. Google의 ML Kit 데이터 공개 문서에 따르면 ML Kit는 다음 정보를 Google로 전송합니다.

| 항목 | 내용 |
|---|---|
| 이전받는 자 | Google LLC (ML Kit 및 Firebase 설치·원격 구성 서비스) |
| 이전되는 국가 | 미국 등 Google이 서버를 운영하는 국가 |
| 이전 항목 | 기기 정보(제조사, 모델, OS 버전, 사용 가능한 ML 하드웨어 가속기), 앱 정보(패키지 이름, 앱 버전), 설치별 식별자(이용자나 실제 기기를 고유하게 식별하지 않음), 성능 지표(처리 시간 등), 기능 설정·입출력 크기·기능 버전, 이벤트 유형, 오류 코드 |
| 이전 목적 | ML Kit 기능의 문제 진단 및 사용 현황 분석, 기능 구성 |
| 이전 시기·방법 | 앱에서 ML Kit가 동작할 때 인터넷을 통해 수시로, HTTPS로 암호화해 전송 |
| 보유·이용 기간 | Google의 정책에 따름 ([Google 개인정보처리방침](https://policies.google.com/privacy)) |

이 정보에는 카메라 영상이나 이용자의 이름·연락처 등은 포함되지 않습니다. ML Kit는 위 정보를 서드 파티에 제공하지 않는다고 밝히고 있습니다. 운동 미션을 사용하지 않거나 기기의 인터넷 연결을 끄면 전송을 줄이거나 막을 수 있습니다.

참고: [ML Kit 데이터 공개 안내](https://developers.google.com/ml-kit/android-data-disclosure?hl=ko)

## 5. 개인정보의 제3자 제공

개발자는 이용자의 개인정보를 수집하지 않으므로 제3자에게 제공하지 않습니다. 위 4항의 ML Kit 전송 외에 다른 광고·분석 SDK는 사용하지 않습니다.

## 6. 앱이 요청하는 권한

| 권한 | 용도 | 필수 여부 |
|---|---|---|
| 카메라 | 운동 미션의 동작 인식 | 운동 미션 사용 시 필요 (거부해도 일반 알람은 사용 가능) |
| 캘린더 읽기 | 폰 달력의 공휴일 달력 확인 | 선택 (거부해도 앱 내장 규칙과 공휴일 업데이트로 동작) |
| 알림 | 알람 화면·다시 알림·놓친 알람 안내 | 알람 표시에 필요 |
| 정확한 알람, 전체 화면 알림 | 정해진 시각에 잠금 화면 위로 알람 화면 표시 | 알람 앱 핵심 기능 |
| 진동, 화면 켜짐 유지, 포그라운드 서비스 | 알람이 울리는 동안 소리·진동 재생 | 알람 앱 핵심 기능 |
| 부팅 완료 수신 | 재부팅 후 알람 다시 등록 | 알람 앱 핵심 기능 |
| 인터넷, 네트워크 상태 | 공휴일 목록 업데이트, ML Kit | 자동 허용 |

## 7. 개인정보의 파기

앱이 기기 안에 저장한 정보는 앱을 삭제하거나, 휴대폰 설정 > 애플리케이션 > 운동 알람 > 저장공간 > 데이터 삭제를 하면 즉시 모두 삭제됩니다. 이용자가 내보낸 백업 파일은 이용자가 직접 삭제할 수 있습니다.

## 8. 이용자의 권리

이용자는 언제든지 앱 안에서 알람·휴무일을 삭제하거나 개인 보정을 초기화할 수 있고, 위 7항의 방법으로 모든 데이터를 지울 수 있습니다. 권한은 휴대폰 설정에서 언제든 철회할 수 있습니다. 개인정보와 관련한 문의는 아래 연락처로 보내 주세요.

## 9. 아동의 개인정보

앱은 만 14세 미만 아동을 대상으로 하지 않으며, 아동의 개인정보를 알면서 수집하지 않습니다.

## 10. 안전성 확보 조치

앱의 데이터는 다른 앱이 접근할 수 없는 앱 전용 저장 공간에 저장됩니다. 인터넷 통신은 HTTPS로 암호화됩니다.

## 11. 개인정보 보호책임자 및 문의

- 개인정보 보호책임자: [개발자 이름]
- 이메일: [문의 이메일]

개인정보 침해에 대한 신고나 상담은 개인정보침해신고센터(privacy.kisa.or.kr, 국번 없이 118), 개인정보분쟁조정위원회(www.kopico.go.kr, 1833-6972)에 문의할 수 있습니다.

## 12. 처리방침의 변경

이 방침이 바뀌면 시행일과 함께 이 페이지에 공지합니다.

---

# FitAlarm Privacy Policy (English summary)

Effective date: October 29, 2026

- FitAlarm has no accounts and the developer runs no servers. **The developer does not collect or store your personal data.**
- Camera frames are processed **on your device only** to count exercise repetitions; images are never stored or transmitted.
- If you allow calendar access, the app reads only **holiday calendars** on your device; personal calendars are not read.
- Alarms, days off, workout records and calibration values are stored only in the app's private storage and are deleted when you uninstall the app or clear its data.
- Once a day the app downloads Google's public "Holidays in South Korea" calendar to pick up newly designated holidays. No personal data is sent (your IP address is visible to Google as with any internet request). You can turn this off in Settings.
- The app uses **Google ML Kit** for on-device pose detection. According to Google's ML Kit data disclosure, ML Kit sends device information (manufacturer, model, OS version, available ML accelerators), app information (package name, app version), a per-installation identifier that does not uniquely identify a user or physical device, performance metrics, API configuration, feature version, event types and error codes to Google over HTTPS, for diagnostics and usage analytics. It does not include camera images. See Google's [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [Privacy Policy](https://policies.google.com/privacy).
- No advertising or other analytics SDKs are used. The app is not directed at children under 14.
- Contact: [Developer name], [Contact email]
