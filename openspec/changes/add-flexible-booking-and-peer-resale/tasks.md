# Tasks

## 25. Tách Owner đặt cá nhân khỏi đặt quầy/check-in (2026-10-28)

- [x] 25.1 Chặn Owner đặt cá nhân ở cơ sở mình tại server: hold/replay, create từ hold, nhóm, linked Customer tại quầy; fail closed khi không xác minh được ownerId. Giữ đặt cá nhân tại cơ sở khác và dữ liệu/đối soát có sẵn.
- [x] 25.2 Mở Owner counter bằng component chung, chọn đúng cơ sở thuộc quyền và đường dẫn rõ tại menu desktop/mobile/sidebar/toolbar. Giữ check-in từ mã/QR khách hoặc lệnh khách vãng lai, không lộ credential.
- [x] 25.3 Gỡ thông báo tỷ lệ hoàn chính sách cũ còn sót tại màn chọn giờ quầy, thống nhất mục24.
- [x] 25.4 Kiểm thử guard/API/UI và hồi quy; typecheck/lint/build, chạy lại bản local và ghi kết quả.

Nghiệm thu mục25: 42 backend tests lượt cuối (Owner paths, guard HTTP và RealBookingIntegrationTest), 128 frontend tests, 21 contract checks đạt; typecheck, lint các file sửa, strict OpenSpec, module budget và production build đạt. Bộ BookingIntegrationTest đầy đủ trước đó chạy199 ca,198 đạt; một assertion mới dùng sai tên cột fixture được sửa thành principal_id và đã chạy lại các ca Owner thành công. Dữ liệu kiểm thử ở PostgreSQL tách biệt. Bằng chứng: `tmp/owner-operations-backend-final-tests.log`, `tmp/owner-operations-frontend-tests.log`, `tmp/owner-operations-contracts.log`, `tmp/owner-operations-typecheck.log`, `tmp/owner-operations-lint.log`, `tmp/owner-operations-backend-build.log`, `tmp/owner-operations-frontend-build.log`. Local3001 trả200 cho các route Owner mới;7 services18080–18086 báoUP và public facility projection có ownerId (`tmp/owner-operations-runtime-smoke.json`). Chưa chạy E2E đăng nhập trên browser hoặc chuyển khoản thật; Cloudflare Tunnel không chạy lúc nghiệm thu, cần khôi phục riêng trước test webhook ngân hàng. Không tạo migration hoặc tự hủy/sửa booking, khoản thanh toán cũ.

## 24. Bỏ hủy theo chính sách và bảo vệ mã check-in (quyết định 2026-10-28)

- [x] 24.1 Chỉ current Customer holder đọc mã10 ký tự/QR; che credential khỏi list/search/detail/operations và mutation/replay vận hành. Giữ nhập mã/quét QR khách cung cấp, CHECKED_IN và luồng nhóm.
- [x] 24.2 Gỡ panel hủy theo chính sách REAL/DEMO và creator/calculator không dùng; endpoint cũ trả409 cho mọi vai trò, không tạo khoản hoàn hoặc hủy booking. Chặn quyền vận hành hủy nháp của Customer khác; giữ nháp của chính người đặt/người tạo quầy và nghĩa vụ lịch sử/thu lỗi.
- [x] 24.3 Khách vãng lai có lệnh check-in không xuất mã, vẫn kiểm đúng quyền cơ sở/khách/đủ tiền/giờ/freeze. Không đổi chủ booking sang Staff.
- [x] 24.4 Kiểm thử API/UI, hồi quy lịch sử cần thiết, contract/typecheck/lint/build; chạy lại local và ghi kết quả thực tế.

Mục 24 thay mọi tác vụ đã hoàn tất bên dưới cho phép Owner/Staff tạo hủy theo chính sách. Test lịch sử SHALL seed dữ liệu, không bật lại chức năng production đã bỏ chỉ để làm fixture.

Nghiệm thu local: frontend production3001 trả200; cả7 dịch vụ18080–18086 báo UP sau khi thay Booking18084. JAR mới không chứa ba class creator/calculator đã xóa; các POST cũ và check-in-guest từ phiên chưa xác thực đều trả401. Bằng chứng: `tmp/owner-checkin-runtime-smoke.json`. Không tự xoay mã check-in đã cấp trước thay đổi; mã đã từng bị xem/chụp vẫn cần được coi là đã lộ.

Kết quả mục 24 (28/10/2026): 230 backend integration tests liên quan trên PostgreSQL tách biệt, 121 frontend tests và 21 OpenAPI contract checks đều đạt; typecheck/lint và production build đạt. Cụ thể Backend: BookingIntegrationTest 194, RealBookingIntegrationTest 33, LegacyCounterBankProfileTest 2 và BankProfileIntegrationTest 1. Test cũ của creator đã bỏ được thay bằng kiểm từ chối/no-write hoặc fixture lịch sử riêng; reader/executor, phân quyền, tiền và báo cáo lịch sử vẫn được kiểm. Search chỉ khớp chính xác mã check-in, không cho dò tiền tố; mọi kết quả search che mã. Customer lấy mã/QR từ trang cá nhân theo holder/freeze guard. Không migration, không đổi giao dịch, khóa SePay hoặc tunnel. Bằng chứng local: `tmp/owner-checkin-final-backend-tests.log`, `tmp/owner-checkin-surefire/`, `tmp/owner-checkin-final-frontend-tests.log`, `tmp/owner-checkin-contract-tests.log`, `tmp/owner-checkin-typecheck.log`, `tmp/owner-checkin-lint.log`, `tmp/owner-checkin-backend-build.log`, `tmp/owner-checkin-frontend-build.log`. Chưa chạy lại chuyển khoản ngân hàng thật hoặc toàn bộ E2E các microservice cho thay đổi này.

Chỉ thực hiện khi người dùng yêu cầu apply change này. Căn cứ proposal, design D1–D13 và9 specs; trạng thái thực hiện được ghi ở các checkbox dưới đây. Không dùng implementation của change cũ làm bằng chứng đã đạt protocol mới. Ghi baseline tại1.6 trước tối ưu; phần code tối ưu ở nhóm14, nghiệm thu tích hợp ở nhóm15.

## 1. Contracts, schema và protocol compatibility

- [x] 1.1 Bổ sung OpenAPI/DTO quote v2, admission errors/retryAt, peer terms/phase/payBy, chat/evidence, A ack/deny, Owner case/presence, bank status/change commands và report assurance; kiểm contract schemas/fixtures gồm150k, BANK_PROFILE_LOCKED/503/first registration, hai bên xác nhận không BANK_VERIFIED và không có phí chuyển nhượng.
- [x] 1.2 Thêm migration Schedule sau version mới nhất cho HOURLY/LEGACY_SLOT, hourly rate/revisions và activation; kiểm fresh/upgrade PostgreSQL, dữ liệu cũ giữ nguyên unit/giá/snapshot và không tự coi pricePerSlot là pricePerHour.
- [x] 1.3 Thêm schema Booking claim/restriction/abandonment/strike, peer usage authority/decisions/one-time rights và bank active pointer/initialized/activation decisions; kiểm FK/unique/check/immutability, legacy COMPLETED guards còn hiệu lực, peer không cần paymentId giả và không có raw STK trong Booking.
- [x] 1.4 Thêm schema Transfer protocol/terms/conversation/evidence/case/money outcome; bổ sung funding/beneficiary lineage và bank command staging/projection trong Payment/Booking khi cần. Kiểm upgrade không sửa historical holder, F/N/H, paidAt hoặc bank evidence; staging không được chọn như tài khoản đã kích hoạt; ghi sơ đồ authority trong contract.
- [x] 1.5 Tách predicates reader/worker/consumer/private commands theo protocol trước bật peer; kiểm event payOS/legacy không tự hoàn tất peer và peer ack không chạy FacilityTransferFinance/movement adapters.
- [x] 1.6 Ghi baseline hiện tại trước sửa code bằng công cụ performance sẵn có: production cold/warm cho search/lịch/booking/report/bank, 5 lượt UI mỗi điều kiện và backend 10 warmups/30 samples; kiểm fixture cùng điều kiện, ghi p50/p95/content-ready/LCP/GET/bytes/query plan/CPU-RAM. Phân biệt dev compile, mock và integrated HTTP REAL; tài liệu nêu dataset/flags/phạm vi, không đo trên DB người dùng.

## 2. Giờ tự chọn và giá giờ

- [x] 2.1 Tách engine quote khoảng từ ScheduleReads, dùng calendar/maintenance/precedence hiện có và sweep biên giá; kiểm15:30–16:30=150000, rule ưu tiên giữa khoảng, thiếu/ambiguous giá và không query từng phút.
- [x] 2.2 Áp min60/max240, bước30 neo đầu khoảng mở, cùng ngày và không qua giờ nghỉ/bảo trì; kiểm request trực tiếp lệch bước/30 phút/270 phút/qua ngày và sân có opening override.
- [x] 2.3 Tính numerator nguyên và làm tròn tổng một lần, phân bổ đoạn ổn định; kiểm101đ/giờ chia4 đoạn vẫn101đ, tổng segment/group allocation đúng tổng và không dùng float.
- [x] 2.4 Thêm quote/hash/pricing revision và hold validation chung; thay guard10 segments bằng duration phù hợp. Kiểm đổi giá/lịch giữa preview→hold, bảo toàn snapshot và không bypass từ route quote/hold cũ.
- [x] 2.5 Thêm orphan warnings và tối đa3 gợi ý hợp lệ từ khoảng trống hiện hành; kiểm ví dụ150k vẫn giữ được, gap30 phút không thành hard reject/strike, gợi ý không vượt4 giờ hoặc đụng booking; cập nhật mô tả quote/owner pricing.

## 3. Quota giữ chỗ chung và lifecycle claims

- [x] 3.1 Cài authoritative admission trong Booking cho online booking+peer acquisition, quota1/user; kiểm2 courts/2 services/concurrent keys chỉ một claim, replay cùng deadline và không dùng Redis count làm nguồn duy nhất.
- [x] 3.2 Nối hold→booking/group organizer→paid/cancel/expiry và peer lease→release/complete vào claim; kiểm không nhân claim khi đổi loại, Staff counter đúng grant có scope riêng và Customer không lách bằng counter=true.
- [x] 3.3 Hoàn thiện lock order/lazy expiry/recovery cho claims; kiểm expireCourt xử hold của user khác, cross-court requests, khai tiền sát hạn và worker restart không deadlock hoặc mở hai claims.
- [x] 3.4 Trả policy/reason/active-resource/retryAt cho FE và private admission/recovery contracts; kiểm source cache lỗi fail closed cho tạo mới nhưng không chặn thanh toán/case cũ, ghi hướng dẫn xử lý claim kẹt.

## 4. Cooldown, anti-regrab và strike ledger

- [x] 4.1 Ghi abandonment event bền vững/unique cho raw release, cancel pending, hold expiry và peer chưa khai; kiểm key retry, hold→booking, expiry nhận lặp chỉ tính một lần và lỗi hệ thống/Owner hủy không bị phạt user.
- [x] 4.2 Cài Redis window/dedup và restriction snapshot phục hồi từ ledger; kiểm lần3 liên tiếp/60 phút nghỉ30 phút, lần5/ngày nghỉ24 giờ, success reset chuỗi nhưng không tổng ngày, out-of-order và qua nửa đêm.
- [x] 4.3 Cài anti-regrab15 phút theo overlap court/interval; kiểm18–19→17:30–18:30 cùng user bị chặn, interval sát nhau `[)` và user khác được đặt, mốc hết15 phút và typed source event ổn định.
- [x] 4.4 Cài finding/strike unique theo Owner decision và market restriction; kiểm lần1/2 cấm30 ngày, lần3 vĩnh viễn, decision replay không tăng strike, thiếu bằng chứng không phạt và booking đã trả/case cũ vẫn được sử dụng/xử lý.
- [x] 4.5 Nối enforcement market create/quote/acquire ở authoritative Booking gate và trạng thái Identity/FE; kiểm race quyết định fraud→mua mới, delayed event không mở bypass và compensating correction có audit; viết policy/counter rebuild runbook.

## 5. Verified account và Gateway rate limit

- [x] 5.1 Lấy ACTIVE/emailVerified/phoneVerified từ Identity nguồn thật và kiểm ở service admission; kiểm chưa verified, inactive/revoked/stale token, header giả và đúng verified email hoặc phone.
- [x] 5.2 Thêm quota5 requests/60 giây/user chung cho hold+mua peer, giữ IP layer; kiểm request6/429/Retry-After, đổi court/route/IP không lách và replenish/burst không biến thành5 mỗi giây.
- [x] 5.3 Giới hạn public routes và bổ sung contract lỗi tiếng Việt; kiểm private admission/evidence/Owner routes không bị mở ở Gateway, Staff sai cơ sở bị chặn và Redis lỗi không ảnh hưởng webhook/đối soát đơn cũ; cập nhật cấu hình mẫu.

## 6. Tài khoản A và QR SePay

- [x] 6.1 Nối profile bank settings/listing terms với encrypted bank destination revisions; kiểm thiếu STK không publish được, số0 đầu/revision người khác/consent, thay profile bị chặn khi còn trách nhiệm và chứng từ cũ không đổi sau lần thay hợp lệ.
- [x] 6.2 Thêm adapter tạo ảnh QR SePay hẹp và private instruction endpoint, memo duy nhất, amount/recipient server; kiểm fixture ảnh decode đúng A/P/memo, timeout/size/origin allowlist và không phát Owner payOS order/F/N.
- [x] 6.3 Tách intended real payment khỏi bank assurance và chỉ nhận booking gốc REAL đủ chứng từ; kiểm DEMO gọi trực tiếp bị chặn, QR/redirect/ảnh/B khai/A ack không ghi BANK_VERIFIED, không cần webhook SePay của A và không cấp quyền trước decision.
- [x] 6.4 Thêm cache instruction theo attempt/hash, no-store/proxy scope và lỗi có thể retry; kiểm polling không gọi SePay mỗi render, raw STK không nằm URL frontend/log và retry không gia hạn payBy; ghi tài liệu giới hạn QR cũ không thu hồi tại ngân hàng.
- [x] 6.5 Cài active bank profile/activation guard theo D13 và status API riêng tư; kiểm khóa BIN/STK/tên/thêm thay thế/xóa/chọn revision khác, reason links đúng quyền, lịch sử đã đóng không khóa và payOS channel không bị gom chung. Viết contract/error guide tiếng Việt.
- [x] 6.6 Nối hold, booking thường/quầy/nhóm, listing/acquisition, case và nghĩa vụ trả/hoàn vào cùng user fence; kiểm đổi ngân hàng chạy đồng thời với từng nguồn chỉ có thứ tự hợp lệ, lock order nhiều user trước court, current holder/beneficiary đúng và Staff created_by không bị khóa hộ khách.
- [x] 6.7 Cài first-registration và stage→activate→project/replay của bank command; kiểm lịch sử nhiều revisions, initialized không reset sau xóa pointer, key/fingerprint/version, activation reply lost/restart và direct bind staging không lách. Resolve revision cũ vẫn dùng được cho chứng từ cũ, không I/O provider/storage trong user/court transaction.
- [x] 6.8 Cài đóng/mở khóa từ các nguồn trách nhiệm thật và fail-closed khi thiếu coverage; kiểm hold hết hạn worker chậm, quyết định cuối quyền sân gỡ riêng khóa case dù money case còn mở, nghĩa vụ A trả B đã xác nhận nhưng chưa xong và refund PENDING/UNKNOWN vẫn khóa riêng, tất cả nguồn đóng mới được đổi và Owner/Staff không override cá nhân. Ghi runbook stuck restrictions/reconcile nguồn, không tự xóa ledger.

## 7. Chat và evidence riêng tư

- [x] 7.1 Cài conversation A–B/listing và cursor/idempotent messages theo pattern play group, không chung membership; kiểm hai buyer cách ly, retry không nhân phòng/tin và hết hold vẫn nhắn được nhưng không giữ sân.
- [x] 7.2 Cài upload/finalize JPEG/PNG private với validator/MinIO hiện có,5 MiB/20MP/max3 ảnh; kiểm MIME giả, pixel bomb, upload chưa READY, wrong uploader/attempt, lỗi storage và dọn staged orphan mà không xóa case đang mở.
- [x] 7.3 Cài claim evidence/reference immutable và private read ACL A/B/Owner case; kiểm public URL/IDOR/Owner cơ sở khác/Staff không được phép, không sửa/xóa biên lai đã nộp để đổi lịch sử.
- [x] 7.4 Cài limits10 tin/phút/phòng và10 ảnh/giờ/user, cursor max100 và safe text; kiểm spam/XSS/session switch/401-403, ghi contracts chat/evidence và retention/privacy.

## 8. Booking peer authority, one-time handoff và check-in

- [x] 8.1 Thêm protocol-aware peer phase/lease/decision commands trong Booking, giữ v1 guards; kiểm binding actor/attempt/terms/hash/version/expiry, không dùng paymentId giả và không có I/O QR/storage dưới court transaction.
- [x] 8.2 Cài successful resale guard trên lịch sử+one-time right ở register/quote/acquire/commit; kiểm A→B thành công chặn B→C, expired attempts không tiêu quyền, retry và concurrent handoff chỉ một completed.
- [x] 8.3 Commit holder/slot holder/code/token/decision/claim/outbox nguyên tử từ seller ack hoặc Owner adjudication hợp lệ; kiểm crash/response lost, original creator/price/payment proofs không đổi và duplicate command không xoay token lần nữa.
- [x] 8.4 Áp freeze và tiền sân gốc cho issuance/QR/mã/HTTP check-in/cancel/edit; kiểm thiếu R, A dùng token cũ, B chỉ khai tiền, canceled/checked-in/end-of-play và lifecycle CHECKED_IN→COMPLETED; cập nhật mô tả quyền vận hành/sở hữu.

## 9. Phiên mua và xác nhận hai bên

- [x] 9.1 Nối new listing/acquisition PEER_RESALE_V1 chỉ cho booking REAL đã đủ tiền với P≤R, bank/policy consent, admission lease/conversation; kiểm DEMO không tạo P2P thật, A mua chính mình, quota1, stale version và legacy không tự đổi recipient. Giữ booking DEMO hiện có theo luồng cũ.
- [x] 9.2 Cài payBy5 phút tối đa và expiry/reopen có điều kiện đúng attempt; kiểm chat-only timeout, upload/declare boundary, retry/tab close không gia hạn và event hết hạn cũ không unlock buyer mới.
- [x] 9.3 Cài B declare ảnh READY+mã bắt buộc, A confirm đủ P/deny và B complaint; kiểm impersonation, body tự thêm amount/holder, PAYMENT_DECLARED không tự expiry5 phút và chỉ authoritative handoff mới hoàn tất quyền.
- [x] 9.4 Cài command status/retry/reconciliation cho Transfer projection và tiền đến/khai muộn; kiểm crash trước/sau RPC commit, late report không khóa phiên mới hoặc lật completed sale; viết hướng dẫn từng trạng thái mua/bán.

## 10. Owner tranh chấp, presence và timeout

- [x] 10.1 Cài case/evidence timeline và Owner scoped ACL chỉ phân xử QUYỀN SỬ DỤNG, preserve finding/reason/version/audit; kiểm A/B bổ sung chứng cứ, Staff/Admin không tự phân xử hoặc đọc ảnh ngoài scope, Owner khác không thấy ảnh và case không có lệnh chi tiền.
- [x] 10.2 Cài attendance do Owner/Staff BOOKING_CHECK_IN ghi ở cơ sở, tách khỏi quyền sử dụng; kiểm user tự khai/online/chat không tính có mặt, effective cutoff/presence write race và audit chống backdate tùy ý.
- [x] 10.3 Nối Owner award B/A retains/intentional-fraud với usage decision/restriction ledger; kiểm inconclusive không strike, replay không tăng finding, ack/resolve/check-in/default chỉ một kết quả. Từ chối moneyDisposition/returnAmount/recipient trong lệnh Owner, không tạo refund hoặc Owner payout từ dispute.
- [x] 10.4 Cài startsAt default A khi cả hai vắng và ngoại lệ Owner bàn giao phần còn lại khi có bên đến đúng giờ; kiểm before/exact/after startsAt, worker nghỉ/restart, endsAt/đã check-in/đã final không phục hồi quyền và không tự bù giờ; ghi runbook hỗ trợ tại sân.
- [x] 10.5 Cài khai/xác nhận trả tiền A→B hoặc hai bên xác nhận hết yêu cầu, tách usage và Owner H; kiểm A khai chưa đủ, chỉ B xác nhận nhận, Owner không ghi/đóng thay tiền, không áp r/fee. Giữ lịch sử tiền sau giờ chơi; QR cũ/late payment không freeze phiên mới hoặc lật handoff đã commit.

## 11. Hoàn booking và báo cáo

- [x] 11.1 Không cấp quyền hoàn mới sau handoff; bỏ calculator P→H và chặn quote/cancel của Customer/Owner/Staff, giữ original/group/counter ngoài phạm vi.
- [x] 11.2 Giữ funding capacity và destination cho nghĩa vụ đã ghi nhận; không tạo khoản hoàn mới cho người mua lại. Owner/Staff BOOKING_REFUND chỉ thực hiện nghĩa vụ hợp lệ.
- [x] 11.3 Báo cáo R của cơ sở và P riêng, không fee/N/H mới từ P2P. H lịch sử vẫn hiện đúng khi thực hiện; nguồn thiếu hiển thị PARTIAL/UNAVAILABLE.
- [x] 11.4 Nối used minutes/freeze/late handoff và date/protocol filters vào chart/bảng/giao dịch; kiểm chỉ sử dụng sau check-in thật, endsAt không đổi, không cộng P vào native bank cash hoặc KPI SportHub; bổ sung hướng dẫn đọc số liệu.

## 12. Frontend đầy đủ luồng mới

- [x] 12.1 Đọc Next docs cài trong dự án và thay availability selection/Owner HOURLY pricing preview; kiểm quote150k, min/max/step, warning mềm/gợi ý, debounce/stale response và sửa giờ không lặp hold.
- [x] 12.2 Thêm bank settings trong account, sửa listing-form/detail/acquisition theo protocol thay DEMO-only guards; kiểm missing bank, consent reset khi giá/bank đổi, public không lộ STK và buyer đã nhận không còn nút/đường API resell.
- [x] 12.3 Làm chat+QR+countdown+upload/reference+B declare/A confirm-deny, nối polling dùng chung có cursor/deadline/visibility/abort; kiểm mobile/a11y/reload/relogin/QR error và request không chồng.
- [x] 12.4 Làm Buyer complaint/evidence/status và Owner case/presence/usage decision screens; kiểm case/frozen/cutoff, tiền A/B chưa rõ khác usage final, Owner không có nút nhập số tiền/hoàn P2P, Staff chỉ presence và forbidden/cross-facility views. A/B tự dùng luồng xác nhận trả tiền riêng.
- [x] 12.5 Làm admission ban/cooldown/anti-regrab messaging và report peer separation; kiểm lý do/retryAt/link lượt đang giữ,30 ngày/vĩnh viễn, user vẫn vào booking đã trả, token/account switch không giữ bank/ảnh của user cũ.
- [x] 12.6 Giữ idempotency qua double click/response loss và lazy route boundaries; kiểm browser thật không tự retry mutation sau401/403, không import bank/chat/evidence nặng vào route chung, checklist UI tiếng Việt/empty/loading/error được thực hiện.
- [x] 12.7 Nối bank status cho account/listing/refund và các form dùng chung; kiểm chỉ khai lần đầu được phép khi có booking, khóa cả nút thêm/dropdown thay thế, hiển thị lý do/liên kết chưa xong, stale tab bị409 rồi refresh và503 không hiển thị được phép. Kiểm session đổi không lộ tài khoản/lý do của người trước và xác nhận Guest vẫn dùng luồng riêng.

## 13. Cutover, cấu hình và vận hành

- [x] 13.1 Thêm feature flags theo facility/protocol cho HOURLY activation và peer create; kiểm chưa có coverage không đặt flexible, reader/worker hiểu cả cohort, tắt tạo mới vẫn reconcile legacy/case/hold đang mở; cập nhật env/Compose/contracts không chứa secret.
- [x] 13.2 Cài reconcile grandfather claims, history resale eligibility và bank initialized/active bindings/responsibilities từ dữ liệu thật; kiểm hold legacy không bị hủy/phạt hồi tố, booking đã resale không bán mới, không ép paid legacy vào peer hoặc coi hồ sơ nhiều revision là lần khai đầu. Hồ sơ ngân hàng không rõ phải được đánh dấu reconcile, không tự chọn STK khác.
- [x] 13.3 Chuẩn bị Owner migration preview/consent cho giá HOURLY và listing chưa khóa, giữ lịch sử pricing/F/N/H; kiểm không silent recipient/rate conversion và rollback không mất money/evidence.
- [x] 13.4 Bàn giao runbook Redis rebuild/stuck claims, SePay QR lỗi, A nhận nhưng không hoàn, Owner timeout/presence và dispute evidence retention; kiểm từng bước có expected outcome và không hứa bank auto-verify/thu hồi QR.

## 14. Tối ưu theo số đo, giữ đúng nghiệp vụ

- [x] 14.1 Thu hẹp invalidation của command đã biết bằng read-state/single-flight hiện có; kiểm chat POST không kéo GET lịch/report/banks, bank/quyền/tiền mutations vẫn refresh mọi tài nguyên liên quan, unknown mutation fallback an toàn và401/403/account switch không replay/lộ dữ liệu. Đo số request trước/sau và cập nhật mapping tài nguyên.
- [x] 14.2 Tối ưu REAL report theo trace: batch source/court/receipt, lookup collections thay lặp tìm kiếm và chỉ thêm index có EXPLAIN; kiểm tổng/ordering/cutoff/coverage cùng facts, outstanding trước kỳ còn đủ, pagination không gửi toàn lịch sử. Ghi query count/rows/p50/p95 với fixture REAL lớn và regression cả legacy/peer.
- [x] 14.3 Đo và tối ưu search/lịch/quote/orphan/bank-guard reads theo khoảng/existence và concurrency hữu hạn; kiểm không query từng phút/N+1 hay scan toàn lịch sử user, quote150k/hold overlap/configuration fence/quota vẫn đúng. Giữ phương án nếu không có cải thiện được chứng minh, ghi quyết định và query plans.
- [x] 14.4 Đo metadata bank/runtime và waterfall report/focus; cache bank metadata có scope phiên, join reads và xử refresh catalog ngoài monitor nếu được trace xác nhận cần. Kiểm stale/timeout/validation/auth semantics, một refresh có giới hạn và không cache bank permission/money/holder để làm nhanh; ghi số GET/wait trước/sau.
- [x] 14.5 Rà bundle/render/ảnh/font của route đã đo, lazy-load phần nặng khi dùng và giữ cursor/bounded messages; kiểm initial JS không chứa chat/camera/chart ngoài nhu cầu, lịch sử chat dài không tải toàn bộ, nội dung chính/mobile/a11y/error states vẫn đúng. Đo resource bytes/content-ready, không chỉ giảm số dòng hoặc tăng số threads.
- [x] 14.6 Chạy lại benchmark production cùng dataset/flags/behavior/cold-warm, mở rộng fixture peer sau khi chức năng tồn tại để so tối ưu tương đương; kiểm giảm≥20% content-ready hoặc p95 của nút thắt đã chọn, route kiểm soát không tăng quá max(10%,50ms), tiền/quyền/coverage không đổi. Bàn giao before/after JSON và hướng dẫn chạy lại; nếu thiếu mục tiêu ghi phần chưa đạt, không đánh dấu hoàn tất bằng kết quả mock cũ.

## 15. Tích hợp toàn hệ thống và nghiệm thu

- [x] 15.1 Thêm E2E isolated qua BFF/Gateway/services/PostgreSQL/Redis/Rabbit/MinIO và SePay image fixture; kiểm15:30–16:30=150k, R REAL payOS, DEMO không P2P thật, B khai→A nhận→handoff→B check-in và B không resell.
- [x] 15.2 Thêm E2E anti-hoarding: cross-service simultaneous holds,3/5 abandonments, anti-regrab overlap, account verified/user rate limits, Redis reset/outage và migrate grandfather; kiểm không double claim hoặc phạt vì retry/system error.
- [x] 15.3 Thêm E2E dispute faults: A không xác nhận/B complaint/A deny/fake evidence, Owner usage-only/strike/replay, hai bên vắng/one present/Owner late, late report/new buyer và broker/response loss; kiểm không Owner refund/payout P2P, holder/token/claim nhất quán và tiền chỉ A/B xác nhận.
- [x] 15.4 Chạy regression liên quan sau tối ưu, frontend tests/typecheck/lint/format/build fixture, module budget≤500 và OpenAPI/OpenSpec strict; ghi lệnh/kết quả mới, phân biệt13 lỗi Booking baseline cũ nếu vẫn tồn tại, không nới test để che lệch nghiệp vụ.
- [x] 15.5 Bàn giao bộ UAT cho người dùng tự scan QR SePay kiểm A/P/memo, thực hiện xác nhận/case theo giá trị nhỏ đã đồng ý; kiểm receipt/decision/report và redacted evidence. Không tự chuyển khoản thật, không thu credential ngân hàng hoặc gọi tiền thật từ CI.
- [x] 15.6 Thêm E2E bank locking qua service thật: khai lần đầu khi có booking, thay ngân hàng với booking/quầy/nhóm/listing/case/refund, race/timeout/restart/stale FE và tất cả nguồn kết thúc. Kiểm không tạo recipient mới trái phép, không khóa Staff hộ khách, Guest/privacy đúng, quyết định cuối quyền sân gỡ riêng khóa case và nghĩa vụ trả/hoàn đã xác nhận vẫn khóa độc lập.

## 16. Review corrections — 2026-10-27

- [x] 16.1 Chat mở trang tin mới nhất, tải lịch sử qua before khi người dùng yêu cầu; after chỉ lấy tin mới. Giữ ACL, giới hạn trang/bộ nhớ, hủy request khi unmount và bổ sung kiểm thử phòng dài.
- [x] 16.2 Lọc timeout đủ điều kiện trước LIMIT 100; kiểm PostgreSQL có 100 phiên có attendance chưa đến endsAt không che các phiên hết hạn, kể cả mốc payBy/startsAt/endsAt và attendance muộn.
- [x] 16.3 Kiểm tra và cấu hình local cho hold protection/bank enforcement; xác minh bằng kiểm thử và ghi rõ prerequisites REAL/P2P còn thiếu, không tự tạo chứng từ thu thật hoặc bật thu tiền khi chưa có kênh Owner.

Kết quả 27/10/2026: 110 frontend tests, 31 backend tests chọn lọc trên PostgreSQL/Redis tách biệt, 6 OpenAPI checks đều đạt; typecheck/lint và production build đạt. Chrome production fixture đạt, không chuyển tiền thật. Smoke qua BFF local xác nhận admission COMPLETE/quota 1 và bank-profile 200/cho khai lần đầu. Bằng chứng trong `tmp/latest-proposal-fixes-*.log`, `tmp/latest-proposal-fixes-smoke.json`, `tmp/latest-proposal-fixes-browser/result.json` (local, không chứa token/khóa). Runtime vẫn DEMO, P2P allowlist trống: database hiện chưa có kênh payOS/booking REAL và chưa có webhook HTTPS; chưa nghiệm thu giao dịch ngân hàng thật. Hai guard đã bật, khóa mã hóa chỉ lưu riêng trong `.env`; bản sao cấu hình cũ được giữ trong `tmp/`.

## Workflow follow-up

- Proposal/design/specs/tasks này phải được người dùng xem lại; chỉ bắt đầu apply khi có yêu cầu mới.
- Không tự archive/sync change này hoặc các change đang tồn tại; khi được yêu cầu thì giữ phân biệt peer/legacy và không xóa lịch sử tiền để giải quyết xung đột spec.

## 17. Bỏ quyền hoàn riêng cho người mua lại (quyết định 2026-10-27)

- [x] 17.1 Gỡ khối Customer trong ảnh, form hoàn sau chuyển nhượng và quảng bá tỷ lệ hoàn liên quan.
- [x] 17.2 Xóa calculator resale refund; chặn quote/cancel mới cả REAL/DEMO và Owner/Staff; giữ lịch sử, nghĩa vụ đã chốt và hoàn vận hành khác.
- [x] 17.3 Kiểm thử UI, API từ chối/cạnh tranh, hoàn lịch sử và báo cáo; build lại bản chạy local.

## 18. Mời nhóm tại giao diện thanh toán

- [x] 18.1 Gỡ panel hoàn dành cho người mua chuyển nhượng khỏi trang nhóm; sửa nhãn form vận hành cơ sở còn dùng.
- [x] 18.2 Hiển thị mã mời/sao chép tại phần thanh toán nhóm, tự cập nhật thành viên và nút Owner chốt chia đều; giữ chia tùy chỉnh và khóa sau khi bắt đầu thanh toán.
- [x] 18.3 Kiểm thử mã mời, poll, chốt thủ công, double click, trạng thái khóa; build và kiểm tra giao diện mới.

Bằng chứng giao diện nhóm: `tmp/group-payment-browser/result.json`, `invite-and-members.png`, `split-payment.png` (production UI với API fixture, không sửa dữ liệu thật). 113 FE tests, 10 OpenAPI contract tests, typecheck/lint và production build đạt; không gọi chuyển tiền thật.

Kiểm chứng backend: `tmp/remove-resale-refund-be-final.log` (24 tests đạt), `tmp/refund-eligibility-final.log` (13 tests kiểm lại phân quyền/cohort đạt), `tmp/refund-booking-package.log`. Đã đóng gói lại booking-service, kiểm JAR không chứa PeerCancellationBasis; 7 health endpoints UP, frontend3001 trả200. Không sửa migration hoặc dữ liệu tài chính lịch sử.

## 19. Sửa mục audit 1–2 (2026-10-27)

- [x] 19.1 Sửa mô tả hoàn trên form REAL, thông báo hủy trực tiếp và bản dịch phản hồi cũ; không hướng người mua lại sang luồng hoàn đã bỏ.
- [x] 19.2 Bỏ kịch bản REAL tạo mới Owner thu hộ/A→B→C; tên lệnh cũ trỏ về suite P2P hiện hành. Kiểm B→C bị từ chối đúng lý do và giữ nguyên holder/version; không khôi phục luồng tạo phí/Owner payout mới. Các nghĩa vụ lịch sử vẫn được kiểm riêng ở backend.
- [x] 19.3 Chạy kiểm thử liên quan, E2E trên hạ tầng tách biệt và dựng bản local mới; không đổi dữ liệu người dùng.

Kết quả mục audit 1–2: 114 frontend tests, 6 backend tests chọn lọc và 17 kịch bản P2P tích hợp qua BFF/Gateway/services đều đạt. Chạy lệnh REAL với tên script cũ vẫn vào suite P2P; không tạo giao dịch Owner thu hộ/A→B→C. Bằng chứng: `tmp/audit-fix-1-2-final-frontend.log`, `tmp/audit-fix-1-2-backend.log`, `tmp/audit-fix-1-2-peer-system-final/browser-api.log`. PayOS/SePay chỉ là fixture loopback; không chuyển tiền thật, không sửa dữ liệu người dùng. Đã build lại frontend local và booking-service; overlay clock chỉ ở staging test, không nằm trong source/JAR production.

## 20. Dọn mục audit 3–5 (2026-10-27)

- [x] 20.1 Chuyển kiểm tra nhãn, tiền, đường quay lại đăng nhập và chọn giờ sang code đang chạy; test vận hành gọi module trực tiếp. Xóa 26 file `frontend/src` và fixture giao diện cũ, không sao chép logic cũ để giữ test.
- [x] 20.2 Xóa 29 module không đến được từ entry points Next hiện hành, type chỉ dùng bởi panel cũ và các mục cấu hình tương ứng. Đổi đầu ra sinh SDK sang `frontend/lib/api/generated`, cập nhật ignore/typecheck để không tạo lại cây cũ.
- [x] 20.3 Bỏ acquisition/funding-payer luôn rỗng trong cơ sở tính hoàn và nhánh tạo phân bổ hoàn sau chuyển nhượng. Giữ định dạng fingerprint của quote đã cấp, schema/history reader, hoàn vận hành/thu lỗi và nghĩa vụ cũ; fixture lịch sử tự seed dữ liệu thay vì giữ nhánh production đã bỏ. Test hoàn nhóm/0% do cơ sở thực hiện, không khôi phục quyền tự hủy nhận hoàn của Customer.
- [x] 20.4 Chạy kiểm thử, typecheck/lint/format/module budget/OpenSpec, production build và smoke bản local; kiểm browser chọn giờ trên bản mới. Không sửa migration hoặc dữ liệu người dùng.

Kết quả mục audit 3–5: xóa 56 file cũ/không dùng (12.803 dòng vật lý), chuyển kiểm thử sang source hiện hành. 112 frontend tests, 17 backend tests chọn lọc trên PostgreSQL/Redis tách biệt và 18 OpenAPI checks đều đạt; typecheck, lint, format frontend, module budget, OpenSpec strict và production build đạt. Chrome trên production build kiểm giá 150.000đ, lựa chọn 30 phút, cảnh báo khoảng trống và giữ chỗ theo quote; 7 health endpoints UP, frontend3001 trả200. Bằng chứng: `tmp/cleanup-3-5-frontend-final.log`, `tmp/cleanup-3-5-backend-final.log`, `tmp/cleanup-3-5-browser/result.json`, `tmp/cleanup-3-5-health.json`. Không chạy toàn bộ backend suite hoặc đo benchmark hiệu năng mới; không suy ra tốc độ tải từ số dòng đã xóa. Không chuyển tiền thật, không đổi migration/dữ liệu người dùng.

## 21. SePay cho cả hai luồng và đóng đường phí cũ (quyết định mới nhất 27/10/2026)

- [x] 21.1 Đổi khoản thu đặt sân/nhóm mới sang QR SePay và webhook đủ tiền, giữ xác nhận B→A riêng. Thêm cấu hình Owner, secret mã hóa/write-only và migration bổ sung bảo toàn sổ lịch sử; không dùng chứng từ DEMO thành REAL.
- [x] 21.2 Gỡ nút/API Customer tự hủy booking đã trả; giữ hủy giữ chỗ chưa thanh toán và hoàn vận hành hợp lệ.
- [x] 21.3 Chặn create/quote/edit/acquire mới theo protocol phí cũ; UI DEMO/legacy chỉ đọc hoặc tiếp tục phiên đã mở, gỡ editor phí Owner. Không đổi nghĩa vụ cũ.
- [x] 21.4 Kiểm thử QR recipient/amount/memo, webhook thiếu/đủ/lặp/sai tài khoản/hết hạn, cashflow chưa áp dụng, lỗi provider, guard quyền, SePay receipt→booking→CHECKED_IN; cập nhật hợp đồng, proposal/design/specs và hướng dẫn cấu hình.
- [x] 21.5 Dựng lại và smoke bản local REAL/SePay; kiểm Chrome với fixture, xác nhận không tự tạo kênh/ngân hàng giả hoặc chuyển tiền thật. Nghiệm thu ngân hàng thật chỉ sau khi Owner cấu hình SePay Live và URL HTTPS công khai.

Kết quả mục 21: 117 frontend tests, 40 backend tests chọn lọc (27 Payment, 6 Booking, 3 Gateway, 4 Transfer), 20 OpenAPI checks và 17 kịch bản tích hợp P2P/SePay qua BFF/Gateway/services đều đạt. Webhook fixture kiểm nhận thiếu/đủ và gửi lặp; QR đúng tài khoản cơ sở/A, bàn giao một lần, tranh chấp chỉ phân xử quyền sân, không tạo phí/payout/hoàn mới sau chuyển nhượng. Typecheck, lint, format, module budget, OpenSpec strict và production build đạt; 7 health endpoints UP, frontend3001 trả200. Bằng chứng: `tmp/sepay-transition-frontend-final.log`, `tmp/sepay-transition-qr-final-tests.log`, `tmp/sepay-transition-system/result.json`, `tmp/sepay-transition-health-final.json`. JAR production không chứa clock fixture. Booking trong ảnh vẫn có payment DEMO và cơ sở chưa cấu hình kênh SePay; không chuyển dữ liệu cũ thành REAL hoặc tự tạo ngân hàng giả. Chưa chạy toàn bộ backend suite hoặc nghiệm thu chuyển tiền ngân hàng thật; Owner cần cấu hình SePay Live và URL HTTPS công khai theo `docs/sepay-payments.md`.

## 22. Cấu hình ngân hàng lần đầu và điều kiện Customer (27/10/2026)

- [x] 22.1 Sửa GET kênh SePay chưa cấu hình trả HTTP204 và nút lưu có type submit; Owner được nhập/lưu lần đầu, vẫn kiểm quyền cơ sở và không lộ khóa. Đồng bộ OpenAPI, regression MVC và API adapter/frontend.
- [x] 22.2 Thông báo lỗi đặt sân chỉ rõ tài khoản cơ sở do Owner cấu hình; không bắt Customer khai ngân hàng để đặt sân/mua lại, chỉ seller cần khai trước khi đăng chuyển nhượng. Thêm lối vào rõ trên cả menu desktop/mobile và hướng dẫn trang ngân hàng.
- [x] 22.3 Chạy kiểm thử liên quan, kiểm luồng REAL không có ngân hàng cá nhân, dựng lại frontend/backend và smoke local; không cấu hình ngân hàng giả trên dữ liệu người dùng hoặc chuyển tiền thật.

Kết quả mục 22: 121 frontend tests, 6 regression backend (3 MVC và 3 client nhận tiền), 20 OpenAPI checks và 19 kịch bản tích hợp qua BFF/Gateway/services đều đạt. Chrome production kiểm Owner mở/lưu kênh đầu tiên từ HTTP204, Customer chưa có ngân hàng vẫn đặt/trả đủ tiền, sau đó khai ngân hàng lần đầu qua form khi đã có lịch; B mua và nhận bàn giao khi chưa khai ngân hàng. Kiểm trình duyệt phát hiện và sửa thêm nút SePay thiếu type submit. Typecheck/lint, production build, module budget và OpenSpec strict đạt; 7 health endpoints UP, frontend3001 trả200. Bằng chứng: `tmp/bank-setup-backend-unit.log`, `tmp/bank-setup-frontend-all-final.log`, `tmp/bank-setup-system-final/result.json`, `owner-first-sepay-setup.png`, `customer-first-resale-bank.png` trong cùng thư mục và `tmp/bank-setup-health-final.json`. JAR production không chứa clock fixture; không tạo kênh/tài khoản giả trên dữ liệu người dùng, không chuyển tiền ngân hàng thật hoặc chạy toàn bộ backend suite.

## 23. Gỡ khối cơ sở trả/hoàn khỏi trang Customer (28/10/2026)

- [x] 23.1 Gỡ ParticipantMoney trên booking, nhóm, danh sách/chi tiết chuyển nhượng; xóa component REAL, nhánh DEMO và dynamic wrapper không còn dùng. Giữ API/backend, dữ liệu và màn hình vận hành của cơ sở.
- [x] 23.2 Chạy kiểm thử hiện có, typecheck/lint, dựng frontend production và smoke bản local; cập nhật bằng chứng kiểm tra.

Kết quả mục 23: 121 frontend tests, typecheck/lint, module budget và OpenSpec strict đều đạt; frontend production đã dựng/chạy lại ở3001, trang Customer trả200 và không còn tham chiếu ParticipantMoney/ParticipantDemoMoney hoặc API mine chỉ phục vụ khối này trong app/features. Bằng chứng: `tmp/remove-participant-money-tests.log`, `tmp/remove-participant-money-typecheck.log`, `tmp/remove-participant-money-lint.log`, `tmp/remove-participant-money-build.log`, `tmp/remove-participant-money-smoke.json`. Không sửa backend hoặc dữ liệu giao dịch; payment-service vẫn UP. Không chạy lại backend suite hoặc thực hiện giao dịch ngân hàng cho thay đổi giao diện này.

## 26. Ẩn token dài và xóa code nghiệp vụ đã bỏ (28/10/2026)

- [x] 26.1 Gỡ text/details token dưới QR booking/điểm danh nhóm, giữ mã10 ký tự/ảnh QR/refresh và quyền chỉ Customer hiện tại; regression render không chứa raw token.
- [x] 26.2 Xóa stub service/controller hủy theo chính sách, OpenAPI/DTO không còn dùng; endpoint đã bỏ trả404 không ghi dữ liệu.
- [x] 26.3 Xóa legacy create/quote/edit/acquire, calculator phí/client/config không còn caller; chuyển coverage lịch sử sang fixture test, kiểm tài khoản chưa xác thực trên PeerTransferAcquisitions đang chạy. Gỡ E2E tái tạo nghiệp vụ phí/hoàn cũ; giữ các reader/executor và guard còn dùng.
- [ ] 26.4 Chạy regression Booking/Transfer/P2P, frontend, hợp đồng/typecheck/lint, OpenSpec/module check; clean build lại và khởi động local. Không sửa dữ liệu thật, migration, cấu hình ngân hàng hoặc Tunnel.

## 27. Giờ tự chọn, lịch trống và nhóm sát giờ (28/10/2026)

- [x] 27.1 Cho chọn giờ bắt đầu/kết thúc, hiển thị khoảng trống liên tục, polling5 giây và chặn lựa chọn bị chiếm; giữ quote/hold phía server.
- [x] 27.2 Báo giá mới theo tỷ lệ thời lượng từ giá/slot gốc, phân đoạn qua biên giá/lịch, làm tròn một lần; giữ tiền booking cũ và kiểm hồi quy.
- [x] 27.3 Hạ mốc tạo nhóm xuống15 phút; hạn góp tiền tối đa30 phút và kết thúc trước giờ chơi5 phút; kiểm biên và rollback.
- [x] 27.4 Hiện tổng tiền thiếu và trả thay; xử lý QR thành viên chưa trả, kiểm quyền, chuyển trùng và không đụng chứng từ đã có tiền.
- [x] 27.5 Chạy kiểm thử liên quan, typecheck/lint, build và khởi động local; kiểm giao diện và API thực tế không chuyển tiền thật.

Kết quả mục 27: 131 frontend tests, 66 backend tests chọn lọc (pricing/schedule, booking và SePay), 21 OpenAPI checks đạt; typecheck/lint, module budget, OpenSpec strict và production build đạt. Đã khởi động bản mới, kiểm API quote 14:30–15:30 và chọn giờ trên giao diện; API báo giá theo tỷ lệ, groupMinimumLeadSeconds=900. Bằng chứng: tmp/near-time-frontend-final-tests.log, tmp/near-time-backend-tests.log, tmp/flexible-final-backend-tests.log, tmp/near-time-contract-tests.log và các build log near-time. Theo yêu cầu bổ sung, đã mở API gateway 18080 qua Cloudflare; public facilities trả200, API groups không đăng nhập trả401. Không chuyển tiền ngân hàng thật hoặc chạy toàn bộ backend suite.

## 28. Thanh toán tiền mặt tại quầy (2026-10-29)

- [x] 28.1 Bổ sung phiếu thu CASH, kiểm quyền hiện tại, khóa nghĩa vụ, retry và đối soát chuyển muộn.
- [x] 28.2 Đổi nhãn Thanh toán tại quầy; thêm nút tiền mặt và xác nhận số tiền trên trang thanh toán/popup.
- [x] 28.3 Kiểm hồi quy quyền, hết hạn, chuyển một phần/trùng, receipt Booking; build và khởi động bản mới.

Kết quả mục 28: 132 frontend tests, 16 Payment tests và 2 Booking integration tests đạt; 21 OpenAPI checks, typecheck/lint, module budget và OpenSpec strict đạt. Backend/frontend production build thành công; migration V19 thêm phiếu thu tiền mặt, không chuyển đổi dữ liệu ngân hàng cũ. Bằng chứng trong tmp/counter-cash-*.log. Chưa thực hiện thao tác ghi nhận tiền mặt trên dữ liệu giao dịch thật.
