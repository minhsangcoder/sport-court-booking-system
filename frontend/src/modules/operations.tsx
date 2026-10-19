"use client";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { Activity, Building2, CalendarDays, Ticket } from "lucide-react";
import { useAuth } from "@/rebuilt/lib/auth-context";
import { useResource, useCurrentTime } from "@/rebuilt/lib/hooks";
import {
  type Access,
  type Booking,
  type Court,
  type Facility,
  type Preview,
  type PriceRule,
  label,
  money,
  shortId,
  time,
  today,
  dateTime,
} from "@/rebuilt/lib/domain";
import {
  Badge,
  Button,
  Empty,
  Field,
  LinkButton,
  Notice,
  PageHead,
  Panel,
  ResourceState,
  Select,
  Stat,
  Table,
  Tabs,
} from "@/rebuilt/components/ui";
import { AvailabilityPage } from "./discovery";
import { BookingDetailPage } from "./booking";
export function useOperationalFacility(permission: string, owner = false) {
  const params = useSearchParams(),
    { user } = useAuth();
  const facilities = useResource<Facility[]>(
      owner && user
        ? "/owner/facilities"
        : !owner && user
          ? "/facilities"
          : null,
    ),
    access = useResource<Access>(!owner && user ? "/users/me/access" : null);
  const choices = owner
    ? facilities.data?.map((f) => ({
        id: f.id,
        name: f.name,
        permissions: [
          "BOOKING_READ",
          "BOOKING_CREATE_COUNTER",
          "BOOKING_CHECK_IN",
          "BOOKING_COMPLETE",
          "SCHEDULE_READ",
        ],
      }))
    : access.data?.facilityBindings
        .filter((b) => b.permissions.includes(permission))
        .map((b) => ({
          id: b.facilityId,
          name:
            facilities.data?.find((f) => f.id === b.facilityId)?.name ??
            `Cơ sở #${shortId(b.facilityId)}`,
          permissions: b.permissions,
        }));
  const [selected, setSelected] = useState(params.get("facilityId") ?? "");
  const current = choices?.find((x) => x.id === selected) ?? choices?.[0];
  return { facilities, access, choices, current, setSelected };
}
export function OperationsPage({
  owner = false,
  view = "bookings",
}: {
  owner?: boolean;
  view?: "bookings" | "schedule" | "counter";
}) {
  const op = useOperationalFacility(
      view === "schedule"
        ? "SCHEDULE_READ"
        : view === "counter"
          ? "BOOKING_CREATE_COUNTER"
          : "BOOKING_READ",
      owner,
    ),
    f = op.current;
  const courts = useResource<Court[]>(
      f
        ? `${owner ? "/owner/facilities/" : "/facilities/"}${f.id}/courts`
        : null,
    ),
    bookings = useResource<Booking[]>(
      f && view !== "counter" && f.permissions.includes("BOOKING_READ")
        ? `/bookings/facility/${f.id}`
        : null,
    );
  const [date, setDate] = useState(today()),
    [court, setCourt] = useState(""),
    [status, setStatus] = useState(""),
    [mode, setMode] = useState("day");
  const c = court || courts.data?.[0]?.id;
  const preview = useResource<Preview>(
      f && c && view === "schedule"
        ? `/schedules/facilities/${f.id}/preview?courtId=${c}&date=${date}`
        : null,
    ),
    availability = useResource<Preview>(
      c && view === "schedule"
        ? `/bookings/availability?courtId=${c}&date=${date}`
        : null,
    ),
    prices = useResource<PriceRule[]>(
      f && view === "schedule" ? `/schedules/facilities/${f.id}/pricing` : null,
    );
  const rows = bookings.data?.filter(
    (b) => (!court || b.courtId === court) && (!status || b.status === status),
  );
  const dated = rows?.filter(
    (b) =>
      new Intl.DateTimeFormat("en-CA", {
        timeZone: b.priceSnapshot.timezone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(new Date(b.startsAt)) === date,
  );
  const details = owner ? "/owner/bookings/" : "/staff/bookings/";
  return (
    <>
      <PageHead
        eyebrow={owner ? "Quản lý lịch đặt" : "Vận hành sân"}
        title={
          view === "schedule"
            ? "Lịch sân & tình trạng phục vụ"
            : view === "counter"
              ? "Đón khách. Chốt lịch chơi."
              : "Lịch đặt tại cơ sở"
        }
        description={
          view === "counter"
            ? "Tra cứu lịch trống, giữ chỗ và tạo booking cho khách tại quầy."
            : "Theo dõi lịch đặt và xử lý theo quyền tại cơ sở của bạn."
        }
      />
      <ResourceState
        resource={owner ? op.facilities : op.access}
        empty={op.choices?.length === 0}
        emptyTitle="Chưa có cơ sở được phân quyền"
        emptyDescription="Bạn cần quyền tương ứng tại cơ sở để thực hiện chức năng này."
      >
        {f && (
          <>
            <Panel>
              <div className="form-grid">
                <Select
                  label="Cơ sở vận hành"
                  value={f.id}
                  onChange={(e) => {
                    op.setSelected(e.target.value);
                    setCourt("");
                  }}
                >
                  {op.choices?.map((x) => (
                    <option key={x.id} value={x.id}>
                      {x.name}
                    </option>
                  ))}
                </Select>
                <Select
                  label={view === "schedule" ? "Sân cần xem" : "Lọc theo sân"}
                  value={view === "schedule" ? (c ?? "") : court}
                  onChange={(e) => setCourt(e.target.value)}
                >
                  <option value="">
                    {view === "schedule" ? "Chọn sân" : "Tất cả sân"}
                  </option>
                  {courts.data?.map((x) => (
                    <option key={x.id} value={x.id}>
                      {x.name}
                    </option>
                  ))}
                </Select>
                {view !== "counter" && (
                  <>
                    <Field
                      label="Ngày vận hành"
                      type="date"
                      value={date}
                      onChange={(e) => setDate(e.target.value)}
                    />
                    <Select
                      label="Trạng thái booking"
                      value={status}
                      onChange={(e) => setStatus(e.target.value)}
                    >
                      <option value="">Tất cả trạng thái</option>
                      {[
                        "PENDING",
                        "CONFIRMED",
                        "CHECKED_IN",
                        "COMPLETED",
                        "CANCELLED",
                        "EXPIRED",
                      ].map((s) => (
                        <option value={s} key={s}>
                          {label(s)}
                        </option>
                      ))}
                    </Select>
                  </>
                )}
              </div>
            </Panel>
            <div style={{ marginTop: 26 }}>
              {view === "counter" ? (
                <ResourceState
                  resource={courts}
                  empty={courts.data?.length === 0}
                  emptyTitle="Chưa có sân để đặt tại quầy"
                  emptyDescription="Cơ sở phải công khai và có sân đang hoạt động."
                >
                  <div className="grid-3">
                    {courts.data
                      ?.filter((x) => !court || x.id === court)
                      .map((x) => (
                        <Panel key={x.id}>
                          <span className="sport-icon">
                            <Activity size={22} />
                          </span>
                          <h3 style={{ margin: "18px 0 8px" }}>{x.name}</h3>
                          <p className="muted small">{x.code}</p>
                          <LinkButton
                            href={`/staff/counter-booking/${x.id}?facilityId=${f.id}`}
                            variant="secondary"
                            arrow
                          >
                            Chọn lịch cho khách
                          </LinkButton>
                        </Panel>
                      ))}
                  </div>
                </ResourceState>
              ) : (
                <>
                  <div className="results-toolbar">
                    <p className="muted small">
                      Booking từ dữ liệu API; thay đổi gần nhất xuất hiện sau
                      khi cập nhật.
                    </p>
                    <div className="actions">
                      <Tabs
                        value={mode}
                        onChange={setMode}
                        items={[
                          ["day", "Theo ngày"],
                          ["week", "Theo tuần"],
                          ["all", "Danh sách"],
                        ]}
                      />
                      <Button
                        variant="secondary"
                        onClick={() => {
                          bookings.reload();
                          availability.reload();
                          preview.reload();
                          prices.reload();
                        }}
                      >
                        Cập nhật lịch
                      </Button>
                    </div>
                  </div>
                  {f.permissions.includes("BOOKING_READ") ? (
                    <ResourceState resource={bookings}>
                      {mode === "week" ? (
                        <div className="panel calendar-scroll">
                          <div className="calendar-days">
                            {Array.from({ length: 7 }, (_, i) => {
                              const d = new Date(date + "T12:00:00+07:00");
                              d.setUTCDate(d.getUTCDate() + i);
                              const day = d.toISOString().slice(0, 10);
                              const events = rows?.filter(
                                (b) =>
                                  new Intl.DateTimeFormat("en-CA", {
                                    timeZone: b.priceSnapshot.timezone,
                                    year: "numeric",
                                    month: "2-digit",
                                    day: "2-digit",
                                  }).format(new Date(b.startsAt)) === day,
                              );
                              return (
                                <div className="calendar-day" key={day}>
                                  <h3>
                                    {day.slice(8)}/{day.slice(5, 7)}
                                  </h3>
                                  {events?.length ? (
                                    events.map((b) => (
                                      <Link
                                        className="calendar-event"
                                        href={details + b.id}
                                        key={b.id}
                                      >
                                        <strong>
                                          {time(
                                            b.startsAt,
                                            b.priceSnapshot.timezone,
                                          )}{" "}
                                          –{" "}
                                          {time(
                                            b.endsAt,
                                            b.priceSnapshot.timezone,
                                          )}
                                        </strong>
                                        <small>
                                          {courts.data?.find(
                                            (c) => c.id === b.courtId,
                                          )?.name ?? shortId(b.courtId)}
                                        </small>
                                        <small style={{ display: "block" }}>
                                          {label(b.status)}
                                        </small>
                                      </Link>
                                    ))
                                  ) : (
                                    <p
                                      className="muted small"
                                      style={{ textAlign: "center" }}
                                    >
                                      Chưa có lịch
                                    </p>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      ) : (
                        <Panel
                          title={
                            mode === "day"
                              ? `Lịch đặt ngày ${date}`
                              : "Danh sách booking"
                          }
                        >
                          {(mode === "day" ? dated : rows)?.length ? (
                            <Table
                              headers={[
                                "Booking / Khách",
                                "Sân",
                                "Thời gian",
                                "Chi phí",
                                "Trạng thái",
                                "Chi tiết",
                              ]}
                            >
                              {(mode === "day" ? dated : rows)?.map((b) => (
                                <tr key={b.id}>
                                  <td>
                                    <strong>#{shortId(b.id)}</strong>
                                    <small>
                                      {b.guestName ??
                                        `Người dùng #${shortId(b.currentHolderId)}`}
                                    </small>
                                  </td>
                                  <td>
                                    {courts.data?.find(
                                      (c) => c.id === b.courtId,
                                    )?.name ?? shortId(b.courtId)}
                                  </td>
                                  <td>
                                    {dateTime(
                                      b.startsAt,
                                      b.priceSnapshot.timezone,
                                    )}
                                    <small>
                                      đến{" "}
                                      {time(b.endsAt, b.priceSnapshot.timezone)}
                                    </small>
                                  </td>
                                  <td>
                                    {money(b.amount, b.currency)}
                                    <small>
                                      {b.paidAt
                                        ? "Đã ghi nhận thanh toán"
                                        : "Chưa ghi nhận thanh toán"}
                                    </small>
                                  </td>
                                  <td>
                                    <Badge state={b.status} />
                                  </td>
                                  <td>
                                    <Link href={details + b.id}>
                                      Mở booking
                                    </Link>
                                  </td>
                                </tr>
                              ))}
                            </Table>
                          ) : (
                            <Empty
                              title="Chưa có booking trong bộ lọc này"
                              description="Thử chọn ngày khác hoặc xem toàn bộ danh sách."
                            />
                          )}
                        </Panel>
                      )}
                    </ResourceState>
                  ) : (
                    <Notice>
                      Bạn được xem lịch trống. Danh sách booking cần quyền xem
                      booking tại cơ sở.
                    </Notice>
                  )}
                  {view === "schedule" && c && (
                    <div className="grid-2" style={{ marginTop: 26 }}>
                      <Panel
                        title="Lịch trống thực tế"
                        description="Bao gồm booking, hold và bảo trì."
                      >
                        <ResourceState
                          resource={availability}
                          empty={availability.data?.slots.length === 0}
                          emptyTitle="Chưa có slot"
                          emptyDescription="Sân chưa mở cửa trong ngày này."
                        >
                          <div
                            className="slot-grid"
                            style={{ gridTemplateColumns: "repeat(3,1fr)" }}
                          >
                            {availability.data?.slots.map((s) => (
                              <div
                                key={s.startsAt}
                                className="slot"
                                style={
                                  s.state !== "AVAILABLE"
                                    ? {
                                        background: "#f1f3ec",
                                        color: "#818b7a",
                                      }
                                    : undefined
                                }
                              >
                                <strong>
                                  {time(
                                    s.startsAt,
                                    availability.data!.timezone,
                                  )}
                                </strong>
                                <small>{label(s.reason ?? s.state)}</small>
                                {s.amount !== null && (
                                  <small>{money(s.amount, s.currency)}</small>
                                )}
                              </div>
                            ))}
                          </div>
                        </ResourceState>
                      </Panel>
                      <Panel
                        title="Bảng giá tham khảo"
                        description="Staff được xem giá, không được chỉnh sửa chính sách."
                      >
                        <ResourceState resource={prices}>
                          {prices.data
                            ?.filter(
                              (p) =>
                                p.active && (!p.courtId || p.courtId === c),
                            )
                            .map((p) => (
                              <div className="detail-list" key={p.id}>
                                <div>
                                  <dt>
                                    {p.label}
                                    <small style={{ display: "block" }}>
                                      {p.startsAt} – {p.endsAt}
                                    </small>
                                  </dt>
                                  <dd>{money(p.pricePerSlot, p.currency)}</dd>
                                </div>
                              </div>
                            ))}
                        </ResourceState>
                        {preview.error && (
                          <Notice tone="error">{preview.error}</Notice>
                        )}
                      </Panel>
                    </div>
                  )}
                </>
              )}
            </div>
          </>
        )}
      </ResourceState>
    </>
  );
}
export function OperationBooking({
  id,
  owner = false,
}: {
  id: string;
  owner?: boolean;
}) {
  const r = useResource<{ booking: Booking }>(`/bookings/${id}`),
    access = useResource<Access>(owner ? null : "/users/me/access");
  const permissions = owner
    ? ["BOOKING_CHECK_IN", "BOOKING_COMPLETE"]
    : (access.data?.facilityBindings.find(
        (b) => b.facilityId === r.data?.booking.facilityId,
      )?.permissions ?? []);
  return <BookingDetailPage id={id} operations permissions={permissions} />;
}
export function CounterCourt({ id }: { id: string }) {
  const context = useResource<{ facility: Facility; court: Court }>(
      `/facilities/courts/${id}/context`,
    ),
    access = useResource<Access>("/users/me/access");
  return (
    <ResourceState resource={context}>
      {context.data && (
        <ResourceState resource={access}>
          {access.data?.facilityBindings.some(
            (b) =>
              b.facilityId === context.data?.facility.id &&
              b.permissions.includes("BOOKING_CREATE_COUNTER"),
          ) ? (
            <AvailabilityPage
              facilityId={context.data.facility.id}
              courtId={id}
              counter
            />
          ) : (
            <Notice tone="error">
              Bạn chưa được cấp quyền đặt tại quầy ở cơ sở này.
            </Notice>
          )}
        </ResourceState>
      )}
    </ResourceState>
  );
}
export function OwnerDashboard() {
  const op = useOperationalFacility("BOOKING_READ", true),
    f = op.current,
    bookings = useResource<Booking[]>(f ? `/bookings/facility/${f.id}` : null),
    courts = useResource<Court[]>(
      f ? `/owner/facilities/${f.id}/courts` : null,
    ),
    { user } = useAuth();
  const now = useCurrentTime();
  const upcoming = bookings.data
    ?.filter(
      (b) =>
        (now === null || new Date(b.endsAt).getTime() > now) &&
        ["PENDING", "CONFIRMED", "CHECKED_IN"].includes(b.status),
    )
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  return (
    <>
      <PageHead
        eyebrow="Quản lý cơ sở"
        title={`Chào ${user?.fullName.split(" ").slice(-1)[0] ?? "bạn"}`}
        description="Lịch đặt và tình trạng sân tại cơ sở của bạn."
        action={
          <LinkButton href="/owner/facilities" variant="secondary" arrow>
            Quản lý cơ sở
          </LinkButton>
        }
      />
      <ResourceState
        resource={op.facilities}
        empty={op.choices?.length === 0}
        emptyTitle="Bắt đầu với cơ sở đầu tiên"
        emptyDescription="Tạo hồ sơ cơ sở rồi thêm sân, lịch và bảng giá."
      >
        {f && (
          <>
            <Panel>
              <Select
                label="Cơ sở đang theo dõi"
                value={f.id}
                onChange={(e) => op.setSelected(e.target.value)}
              >
                {op.choices?.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.name}
                  </option>
                ))}
              </Select>
            </Panel>
            <ResourceState resource={bookings}>
              <div className="grid-4" style={{ margin: "24px 0" }}>
                <Stat
                  label="Lịch sắp tới"
                  value={upcoming?.length ?? "—"}
                  icon={<CalendarDays size={20} />}
                />
                <Stat
                  label="Đang phục vụ"
                  value={
                    bookings.data?.filter((b) => b.status === "CHECKED_IN")
                      .length ?? "—"
                  }
                  icon={<Activity size={20} />}
                />
                <Stat
                  label="Sân đang mở"
                  value={courts.data?.filter((c) => c.enabled).length ?? "—"}
                  icon={<Building2 size={20} />}
                />
                <Stat
                  label="Chờ thanh toán"
                  value={
                    bookings.data?.filter((b) => b.status === "PENDING")
                      .length ?? "—"
                  }
                  icon={<Ticket size={20} />}
                />
              </div>
              <div className="grid-2">
                <Panel
                  title="Cuộc chơi tiếp theo"
                  action={
                    <Link
                      href={`/owner/bookings?facilityId=${f.id}`}
                      className="text-link small"
                    >
                      Xem lịch →
                    </Link>
                  }
                >
                  {upcoming?.length ? (
                    <ol className="timeline">
                      {upcoming.slice(0, 6).map((b) => (
                        <li key={b.id}>
                          <Link href={`/owner/bookings/${b.id}`}>
                            <strong>
                              {courts.data?.find((c) => c.id === b.courtId)
                                ?.name ?? shortId(b.courtId)}
                            </strong>
                          </Link>
                          <small>
                            {dateTime(b.startsAt, b.priceSnapshot.timezone)}
                          </small>
                          <Badge state={b.status} />
                        </li>
                      ))}
                    </ol>
                  ) : (
                    <Empty
                      title="Chưa có cuộc chơi sắp tới"
                      description="Lịch đặt mới sẽ xuất hiện tại đây."
                    />
                  )}
                </Panel>
                <Panel title="Không gian của bạn">
                  <div className="court-list">
                    {courts.data?.map((c) => (
                      <Link
                        href={`/owner/facilities/${f.id}/courts/${c.id}/edit`}
                        key={c.id}
                        className="court-row"
                      >
                        <div>
                          <h3>{c.name}</h3>
                          <p>{c.code}</p>
                        </div>
                        <Badge state={c.enabled ? "ACTIVE" : "DISABLED"} />
                      </Link>
                    ))}
                  </div>
                  {courts.error && <Notice tone="error">{courts.error}</Notice>}
                </Panel>
              </div>
              <p className="muted small" style={{ marginTop: 18 }}>
                Chỉ số tính từ tập booking hiện được API trả về. Xem báo cáo để
                tổng hợp theo kỳ.
              </p>
            </ResourceState>
          </>
        )}
      </ResourceState>
    </>
  );
}
