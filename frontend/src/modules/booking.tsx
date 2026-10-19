"use client";
import { Photo } from "@/rebuilt/components/photo";
import { QuoteBreakdown } from "@/rebuilt/components/quote-breakdown";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ArrowRight, CheckCircle2, Users, Wallet } from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import {
  useAction,
  useCountdown,
  useIdempotency,
  useResource,
} from "@/rebuilt/lib/hooks";
import {
  type Booking,
  type BookingDetail,
  type Court,
  type Facility,
  type Group,
  type Hold,
  type Payment,
  dateTime,
  label,
  money,
  shortId,
  time,
} from "@/rebuilt/lib/domain";
import {
  Badge,
  Button,
  DetailList,
  Feedback,
  Field,
  LinkButton,
  Modal,
  Notice,
  PageHead,
  Panel,
  ResourceState,
  Stepper,
  Tabs,
  TextArea,
} from "@/rebuilt/components/ui";
export function BookingCard({ booking: b }: { booking: Booking }) {
  const facility = useResource<Facility>(`/facilities/${b.facilityId}`),
    courts = useResource<Court[]>(`/facilities/${b.facilityId}/courts`);
  const d = new Date(b.startsAt),
    zone = b.priceSnapshot.timezone;
  return (
    <article className="booking-card">
      <div className="booking-date">
        <span>
          Tháng{" "}
          {new Intl.DateTimeFormat("vi-VN", {
            month: "numeric",
            timeZone: zone,
          }).format(d)}
        </span>
        <strong>
          {new Intl.DateTimeFormat("vi-VN", {
            day: "2-digit",
            timeZone: zone,
          }).format(d)}
        </strong>
      </div>
      <div className="booking-card-main">
        <Badge state={b.status} />
        <h3>{facility.data?.name ?? `Cơ sở #${shortId(b.facilityId)}`}</h3>
        <p>
          {courts.data?.find((c) => c.id === b.courtId)?.name ??
            `Sân #${shortId(b.courtId)}`}{" "}
          · {time(b.startsAt, zone)} – {time(b.endsAt, zone)}
        </p>
      </div>
      <div className="booking-card-price">
        <strong>{money(b.amount, b.currency)}</strong>
        <LinkButton
          variant="secondary"
          href={`/customer/bookings/${b.id}`}
          arrow
        >
          Chi tiết lịch đặt
        </LinkButton>
      </div>
    </article>
  );
}
export function BookingsPage() {
  const resource = useResource<Booking[]>("/bookings"),
    [tab, setTab] = useState("upcoming");
  const filtered = resource.data?.filter(
    (b) =>
      tab === "all" ||
      (tab === "upcoming" &&
        ["PENDING", "CONFIRMED", "CHECKED_IN"].includes(b.status)) ||
      (tab === "completed" && b.status === "COMPLETED") ||
      (tab === "cancelled" && ["CANCELLED", "EXPIRED"].includes(b.status)),
  );
  return (
    <div className="container">
      <PageHead
        eyebrow="Lịch đặt của bạn"
        title="Lịch chơi của bạn"
        description="Những cuộc hẹn trên sân, từ hôm nay đến lần tiếp theo."
        action={
          <LinkButton href="/search" arrow>
            Đặt thêm một cuộc chơi
          </LinkButton>
        }
      />
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          ["upcoming", "Sắp tới"],
          ["completed", "Đã hoàn thành"],
          ["cancelled", "Đã hủy / hết hạn"],
          ["all", "Tất cả"],
        ]}
      />
      <ResourceState
        resource={resource}
        empty={filtered?.length === 0}
        emptyTitle="Chưa có lịch chơi trong mục này"
        emptyDescription="Tìm một sân phù hợp và bắt đầu cuộc chơi tiếp theo."
      >
        <div className="stack">
          {filtered?.map((b) => (
            <BookingCard key={b.id} booking={b} />
          ))}
        </div>
      </ResourceState>
    </div>
  );
}
export function HoldPage({ id }: { id: string }) {
  const resource = useResource<Hold>(`/bookings/holds/${id}`);
  return (
    <div className="container narrow">
      <Stepper current={1} />
      <ResourceState resource={resource}>
        {resource.data && <HoldConfirmation hold={resource.data} />}
      </ResourceState>
    </div>
  );
}
function HoldConfirmation({ hold: h }: { hold: Hold }) {
  const router = useRouter(),
    params = useSearchParams(),
    counter = params.get("counter") === "true";
  const countdown = useCountdown(h.expiresAt),
    action = useAction(),
    key = useIdempotency(),
    [mode, setMode] = useState("individual");
  const facility = useResource<Facility>(`/facilities/${h.facilityId}`);
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (countdown.expired) return;
    const f = new FormData(e.currentTarget);
    await action.run(async () => {
      if (mode === "group" && !counter) {
        const body = {
          holdId: idOr(h),
          name: String(f.get("name")),
          maxMembers: Number(f.get("maxMembers")),
        };
        const group = await api<Group>("/groups", {
          method: "POST",
          headers: { "Idempotency-Key": key(JSON.stringify(body)) },
          body: JSON.stringify(body),
        });
        router.push(`/customer/groups/${group.id}`);
      } else {
        const body = {
          holdId: h.id,
          ...(counter
            ? { guestName: f.get("guestName"), guestPhone: f.get("guestPhone") }
            : {}),
        };
        const b = await api<Booking>(
          `/bookings${counter ? "?counter=true" : ""}`,
          {
            method: "POST",
            headers: { "Idempotency-Key": key(JSON.stringify(body)) },
            body: JSON.stringify(body),
          },
        );
        router.push(
          counter ? `/staff/bookings/${b.id}` : `/customer/bookings/${b.id}`,
        );
      }
    });
  }
  return (
    <>
      <PageHead
        title="Chốt lịch. Sẵn sàng ra sân."
        description="Kiểm tra lại thông tin trước khi tạo lịch đặt."
      />
      {countdown.expired ? (
        <Notice tone="warning">
          Thời gian giữ chỗ đã hết. Hãy chọn lại lịch trống.
        </Notice>
      ) : h.state === "HOLD" ? (
        <Notice>
          Khung giờ đang được giữ cho bạn. Còn{" "}
          <span className="countdown" style={{ fontSize: 16 }}>
            {countdown.text}
          </span>{" "}
          để hoàn tất.
        </Notice>
      ) : (
        <Notice>
          Lượt giữ chỗ này đã được sử dụng hoặc giải phóng. Kiểm tra lịch đặt
          của bạn.
        </Notice>
      )}
      <div className="grid-2">
        <Panel title="Thông tin cuộc chơi">
          <DetailList
            items={[
              ["Cơ sở", facility.data?.name ?? shortId(h.facilityId)],
              ["Ngày chơi", dateTime(h.startsAt, h.quote.timezone)],
              [
                "Khung giờ",
                `${time(h.startsAt, h.quote.timezone)} – ${time(h.endsAt, h.quote.timezone)}`,
              ],
              ["Múi giờ", h.quote.timezone],
              [
                "Tổng chi phí",
                <strong
                  style={{ fontSize: 23, color: "var(--brand)" }}
                  key="price"
                >
                  {money(h.quote.amount, h.quote.currency)}
                </strong>,
              ],
            ]}
          />
          <QuoteBreakdown quote={h.quote} />
        </Panel>
        <Panel
          title={
            counter ? "Thông tin khách tại quầy" : "Bạn muốn chơi thế nào?"
          }
        >
          {!counter && (
            <Tabs
              value={mode}
              onChange={setMode}
              items={[
                ["individual", "Đặt sân cá nhân"],
                ["group", "Đặt sân theo nhóm"],
              ]}
            />
          )}
          <form onSubmit={submit}>
            <fieldset
              disabled={action.busy || countdown.expired || h.state !== "HOLD"}
              className="stack"
            >
              {counter ? (
                <>
                  <Field
                    name="guestName"
                    label="Tên khách"
                    maxLength={180}
                    required
                  />
                  <Field
                    name="guestPhone"
                    label="Số điện thoại khách"
                    maxLength={30}
                    required
                  />
                </>
              ) : mode === "group" ? (
                <>
                  <Field
                    name="name"
                    label="Tên nhóm chơi"
                    placeholder="Buổi chơi cùng đồng đội"
                    maxLength={180}
                  />
                  <Field
                    name="maxMembers"
                    label="Số thành viên tối đa"
                    type="number"
                    min={1}
                    max={50}
                    defaultValue={4}
                    required
                  />
                  <Notice>
                    Sau khi tạo nhóm, bạn có thể mời thành viên và chia chi phí
                    trước khi thanh toán.
                  </Notice>
                </>
              ) : (
                <p className="small muted">
                  Booking sẽ được tạo ở trạng thái chờ thanh toán. Hoàn tất
                  thanh toán trước hạn giữ chỗ để xác nhận lịch.
                </p>
              )}
              <Button busy={action.busy}>
                Xác nhận {mode === "group" && !counter ? "tạo nhóm" : "đặt sân"}
                <ArrowRight size={16} />
              </Button>
            </fieldset>
            <Feedback action={action} />
          </form>
          {!counter && (
            <Notice tone="warning">
              {mode === "group"
                ? "Thành viên và chi phí không thể thay đổi sau khi bắt đầu thanh toán. Hiện chưa hỗ trợ hoàn tiền nhóm."
                : "Bạn có thể hủy lượt đặt trước khi thanh toán. Lượt đặt đã thanh toán hiện không thể hủy hoặc hoàn tiền; có thể chuyển nhượng nếu chính sách cơ sở cho phép."}
            </Notice>
          )}
          <Button
            variant="ghost"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () => api(`/bookings/holds/${h.id}`, { method: "DELETE" }),
                () =>
                  router.push(
                    counter
                      ? "/staff/counter-booking"
                      : `/facilities/${h.facilityId}/courts/${h.courtId}`,
                  ),
              );
            }}
          >
            Bỏ giữ chỗ, chọn lại lịch
          </Button>
          <Link className="text-link small" href="/customer/bookings">
            Xem lịch đã đặt
          </Link>
        </Panel>
      </div>
    </>
  );
}
function idOr(h: Hold) {
  return h.id;
}
export function BookingDetailPage({
  id,
  admin = false,
  operations = false,
  permissions = [],
}: {
  id: string;
  admin?: boolean;
  operations?: boolean;
  permissions?: string[];
}) {
  const resource = useResource<BookingDetail>(
      `${admin ? "/admin/bookings/" : "/bookings/"}${id}`,
    ),
    b = resource.data?.booking,
    { user } = useAuth(),
    router = useRouter(),
    action = useAction(),
    key = useIdempotency(),
    [cancel, setCancel] = useState(false);
  const facility = useResource<Facility>(
      b ? `/facilities/${b.facilityId}` : null,
    ),
    courts = useResource<Court[]>(
      b ? `/facilities/${b.facilityId}/courts` : null,
    );
  const group = resource.data?.history.some(
    (x) => x.action === "GROUP_CREATED",
  );
  const owned = b?.currentHolderId === user?.id;
  useEffect(() => {
    if (!resource.data?.checkinToken || b?.status !== "CONFIRMED") return;
    const timer = setInterval(resource.reload, 60000);
    return () => clearInterval(timer);
  }, [resource.data?.checkinToken, b?.status, resource.reload]);
  async function pay() {
    await action.run(
      () =>
        api<Payment>("/payments", {
          method: "POST",
          headers: { "Idempotency-Key": key("payment:" + id) },
          body: JSON.stringify({ bookingId: id }),
        }),
      (p) => router.push(`/customer/payments/${p.id}`),
    );
  }
  return (
    <div className="container narrow">
      <PageHead
        eyebrow={admin ? "Theo dõi lịch đặt" : "Chi tiết lịch đặt"}
        title={`Lịch đặt #${shortId(id)}`}
        description="Thông tin sử dụng sân và lịch sử xử lý được cập nhật từ hệ thống."
        action={
          <Button variant="secondary" onClick={resource.reload}>
            Cập nhật trạng thái
          </Button>
        }
      />
      <ResourceState resource={resource}>
        {b && (
          <div className="grid-2">
            <article className="ticket">
              <div className="ticket-top">
                <span className="small">Phiếu đặt sân</span>
                <h2>
                  {facility.data?.name ?? `Cơ sở #${shortId(b.facilityId)}`}
                </h2>
                <p>
                  {courts.data?.find((c) => c.id === b.courtId)?.name ??
                    `Sân #${shortId(b.courtId)}`}
                </p>
              </div>
              <div className="ticket-body">
                <Badge state={b.status} />
                <DetailList
                  items={[
                    [
                      "Thời gian",
                      dateTime(b.startsAt, b.priceSnapshot.timezone),
                    ],
                    [
                      "Khung giờ",
                      `${time(b.startsAt, b.priceSnapshot.timezone)} – ${time(b.endsAt, b.priceSnapshot.timezone)}`,
                    ],
                    ["Chi phí", money(b.amount, b.currency)],
                    ["Địa chỉ", facility.data?.addressLine ?? "Xem tại cơ sở"],
                    ["Thanh toán", b.paidAt ? "Đã ghi nhận" : "Chưa ghi nhận"],
                    [
                      "Nguồn đặt",
                      b.source === "OFFLINE_COUNTER"
                        ? "Tại quầy"
                        : "Trực tuyến",
                    ],
                  ]}
                />
                {b.guestName && (
                  <p className="small muted">
                    Khách: {b.guestName} · {b.guestPhone}
                  </p>
                )}
              </div>
              {resource.data?.checkinQrSvg && (
                <div className="ticket-qr">
                  <Photo
                    src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(resource.data.checkinQrSvg)}`}
                    alt="Mã QR check-in của lịch đặt"
                  />
                  <p>
                    Xuất trình mã này tại cơ sở. Không chia sẻ mã với người
                    khác.
                  </p>
                  {resource.data.checkinToken && (
                    <Button
                      variant="secondary"
                      onClick={() => {
                        void action.run(
                          () =>
                            navigator.clipboard.writeText(
                              resource.data!.checkinToken!,
                            ),
                          () =>
                            action.setMessage(
                              "Đã sao chép mã check-in. Gửi trực tiếp cho nhân viên tiếp nhận.",
                            ),
                        );
                      }}
                    >
                      Sao chép mã check-in
                    </Button>
                  )}
                </div>
              )}
            </article>
            <div className="stack">
              <Panel title="Quản lý lịch đặt">
                <Feedback action={action} />
                {group ? (
                  <LinkButton
                    href={`/customer/groups/${id}`}
                    variant="secondary"
                  >
                    Xem nhóm & chi phí <Users size={16} />
                  </LinkButton>
                ) : owned && !admin && b.status === "PENDING" ? (
                  <div className="stack">
                    <p className="small muted">
                      Thanh toán trước {dateTime(b.holdExpiresAt)} để xác nhận
                      lịch.
                    </p>
                    <Button busy={action.busy} onClick={pay}>
                      <Wallet size={17} />
                      Tiếp tục thanh toán
                    </Button>
                    <Button variant="ghost" onClick={() => setCancel(true)}>
                      Hủy lịch đặt
                    </Button>
                  </div>
                ) : owned && !admin && b.status === "CONFIRMED" ? (
                  <div className="stack">
                    <LinkButton
                      href={`/customer/bookings/${id}/transfer`}
                      variant="secondary"
                      arrow
                    >
                      Đăng chuyển nhượng
                    </LinkButton>
                    <Notice tone="warning">
                      Hủy booking đã thanh toán hiện chưa được backend hỗ trợ.
                      Bạn có thể chuyển nhượng nếu đáp ứng chính sách của cơ sở.
                    </Notice>
                  </div>
                ) : (
                  <p className="muted small">
                    {label(b.status)} · Thao tác khả dụng phụ thuộc vào trạng
                    thái và quyền truy cập của bạn.
                  </p>
                )}
                {b.paymentId && !admin && (
                  <Link
                    className="text-link small"
                    style={{ display: "block", marginTop: 16 }}
                    href={`/customer/payments/${b.paymentId}`}
                  >
                    Xem giao dịch thanh toán
                  </Link>
                )}
                {operations &&
                  permissions.includes("BOOKING_CHECK_IN") &&
                  b.status === "CONFIRMED" && (
                    <form
                      className="stack"
                      style={{ marginTop: 20 }}
                      onSubmit={(e) => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        void action.run(
                          () =>
                            api(`/bookings/${id}/check-in`, {
                              method: "POST",
                              body: JSON.stringify({ token: f.get("token") }),
                            }),
                          () => resource.reload(),
                        );
                      }}
                    >
                      <Field
                        name="token"
                        label="Mã check-in từ khách"
                        maxLength={500}
                        required
                      />
                      <Button busy={action.busy}>Xác nhận check-in</Button>
                    </form>
                  )}
                {operations &&
                  permissions.includes("BOOKING_COMPLETE") &&
                  b.status === "CHECKED_IN" && (
                    <Button
                      busy={action.busy}
                      onClick={() => {
                        void action.run(
                          () =>
                            api(`/bookings/${id}/complete`, { method: "POST" }),
                          () => resource.reload(),
                        );
                      }}
                    >
                      Hoàn tất sử dụng sân
                    </Button>
                  )}
              </Panel>
              <Panel title="Hành trình booking">
                <ol className="timeline">
                  {resource.data?.history.map((h) => (
                    <li key={h.id}>
                      <strong>{label(h.action)}</strong>
                      <small>
                        {dateTime(h.createdAt, b.priceSnapshot.timezone)}
                      </small>
                    </li>
                  ))}
                </ol>
              </Panel>
            </div>
          </div>
        )}
      </ResourceState>
      <Modal
        open={cancel}
        title="Hủy lịch đặt này?"
        onClose={() => setCancel(false)}
      >
        <p className="muted small">
          Booking chưa thanh toán sẽ bị hủy và khung giờ được giải phóng.
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void action.run(
              () =>
                api(`/bookings/${id}/cancel`, {
                  method: "POST",
                  body: JSON.stringify({ reason: f.get("reason") }),
                }),
              () => {
                setCancel(false);
                resource.reload();
              },
            );
          }}
        >
          <TextArea name="reason" label="Lý do hủy" required maxLength={500} />
          <Feedback action={action} />
          <div className="form-footer">
            <Button
              type="button"
              variant="secondary"
              onClick={() => setCancel(false)}
            >
              Giữ lịch đặt
            </Button>
            <Button variant="danger" busy={action.busy}>
              Xác nhận hủy
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
export function PaymentPage({ id }: { id: string }) {
  const resource = useResource<Payment>(`/payments/${id}`),
    action = useAction(),
    p = resource.data;
  useEffect(() => {
    if (p?.status !== "PENDING") return;
    const timer = setInterval(resource.reload, 5000);
    return () => clearInterval(timer);
  }, [p?.status, resource.reload]);
  return (
    <div className="container narrow">
      <Stepper current={2} />
      <ResourceState resource={resource}>
        {p && (
          <PaymentDetails
            payment={p}
            reload={resource.reload}
            action={action}
          />
        )}
      </ResourceState>
    </div>
  );
}
function PaymentDetails({
  payment: p,
  reload,
  action,
}: {
  payment: Payment;
  reload: () => void;
  action: ReturnType<typeof useAction>;
}) {
  const countdown = useCountdown(p.expiresAt);
  const href = p.acquisitionId
    ? `/customer/transfers/acquisitions/${p.acquisitionId}`
    : p.memberId
      ? `/customer/groups/${p.bookingId}`
      : `/customer/bookings/${p.bookingId}`;
  return (
    <>
      <PageHead
        eyebrow="Thanh toán"
        title={
          p.status === "SUCCESS"
            ? "Thanh toán đã được ghi nhận."
            : "Hoàn tất thanh toán"
        }
        description={`Giao dịch #${shortId(p.id)}`}
      />
      <div className="grid-2">
        <Panel>
          <div className="payment-icon">
            {p.status === "SUCCESS" ? (
              <CheckCircle2 size={34} />
            ) : (
              <Wallet size={34} />
            )}
          </div>
          <Badge state={p.status} />
          <p className="payment-total">{money(p.amount, p.currency)}</p>
          <DetailList
            items={[
              [
                "Loại giao dịch",
                p.purpose === "TRANSFER"
                  ? "Nhận chuyển nhượng"
                  : p.purpose === "GROUP_CONTRIBUTION"
                    ? "Đóng góp nhóm"
                    : "Đặt sân",
              ],
              ["Nhà cung cấp", p.provider],
              ["Mã tham chiếu", p.providerReference],
              ["Hạn thanh toán", dateTime(p.expiresAt)],
              [
                "Đã thanh toán lúc",
                p.paidAt ? dateTime(p.paidAt) : "Chưa ghi nhận",
              ],
            ]}
          />
          <LinkButton href={href} variant="secondary" arrow>
            Xem trạng thái lịch đặt
          </LinkButton>
        </Panel>
        <Panel title="Trạng thái thanh toán">
          <Feedback action={action} />
          {p.provider === "DEMO" && (
            <Notice tone="warning">
              Môi trường thanh toán demo. Các thao tác dưới đây cập nhật giao
              dịch thử nghiệm; không chuyển tiền thật.
            </Notice>
          )}
          {p.status === "PENDING" && !countdown.expired ? (
            <>
              <p className="muted small">Hoàn tất trong thời gian còn lại</p>
              <p className="countdown" style={{ margin: "15px 0 25px" }}>
                {countdown.text}
              </p>
              {p.provider === "DEMO" ? (
                <div className="stack">
                  <Button
                    busy={action.busy}
                    onClick={() => {
                      void action.run(
                        () =>
                          api(`/payments/${p.id}/demo/complete`, {
                            method: "POST",
                            body: JSON.stringify({ outcome: "SUCCESS" }),
                          }),
                        reload,
                      );
                    }}
                  >
                    Xác nhận thanh toán demo
                  </Button>
                  <Button
                    variant="secondary"
                    busy={action.busy}
                    onClick={() => {
                      void action.run(
                        () =>
                          api(`/payments/${p.id}/demo/complete`, {
                            method: "POST",
                            body: JSON.stringify({ outcome: "FAILED" }),
                          }),
                        reload,
                      );
                    }}
                  >
                    Mô phỏng thanh toán thất bại
                  </Button>
                </div>
              ) : (
                <Notice>
                  Chưa có đường dẫn cổng thanh toán trong response. Liên hệ cơ
                  sở và theo dõi trạng thái giao dịch.
                </Notice>
              )}
            </>
          ) : (
            <p className="small muted">
              {p.status === "SUCCESS"
                ? "Hệ thống đang đồng bộ kết quả vào booking. Kiểm tra trạng thái lịch đặt để xem xác nhận cuối cùng."
                : countdown.expired
                  ? "Giao dịch đã quá hạn. Kiểm tra trạng thái mới nhất trước khi thao tác."
                  : "Giao dịch hiện không thể tiếp tục thanh toán."}
            </p>
          )}
          <Button variant="ghost" onClick={reload} style={{ marginTop: 20 }}>
            Cập nhật giao dịch
          </Button>
          {p.purpose === "TRANSFER" && (
            <Notice>
              Chuyển giao quyền sử dụng và chi trả cho người chuyển là hai trạng
              thái riêng. Backend hiện chưa thực thi chi trả tiền chuyển nhượng.
            </Notice>
          )}
        </Panel>
      </div>
    </>
  );
}
