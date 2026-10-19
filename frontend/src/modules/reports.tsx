"use client";
import { useState } from "react";
import { useResource } from "@/rebuilt/lib/hooks";
import { type Court, label, money, localInstant } from "@/rebuilt/lib/domain";
import {
  Button,
  ExportButton,
  Field,
  Notice,
  PageHead,
  Panel,
  ResourceState,
  Stat,
  Table,
} from "@/rebuilt/components/ui";
import { useFacility } from "./facilities";
type CourtReport = {
  court_id: string;
  status: string;
  count: number;
  booking_value: number;
  reserved_minutes: number;
};
type BookingStats = {
  states: { status: string; count: number; booking_value: number }[];
};
type PaymentStats = {
  orders: {
    purpose: string;
    status: string;
    currency: string;
    count: number;
    amount: number;
  }[];
  escrow: { state: string; currency: string; count: number; amount: number }[];
  refundReviews: { state: string; count: number }[];
};
export function reportRange(form: FormData) {
  const from = String(form.get("from") ?? ""),
    to = String(form.get("to") ?? ""),
    params = new URLSearchParams();
  if (
    from &&
    to &&
    (from > to ||
      (new Date(to).getTime() - new Date(from).getTime()) / 86400000 > 365)
  )
    throw new Error("Chọn khoảng thời gian hợp lệ, tối đa 366 ngày.");
  if (from) params.set("from", localInstant(from + "T00:00:00"));
  if (to) {
    const end = new Date(localInstant(to + "T00:00:00"));
    end.setUTCDate(end.getUTCDate() + 1);
    params.set("to", end.toISOString());
  }
  return params.toString();
}
function RangeFilter({ onChange }: { onChange: (query: string) => void }) {
  const [error, setError] = useState("");
  return (
    <Panel>
      <form
        className="actions"
        onSubmit={(e) => {
          e.preventDefault();
          try {
            onChange(reportRange(new FormData(e.currentTarget)));
            setError("");
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        <Field name="from" label="Từ ngày" type="date" />
        <Field name="to" label="Đến hết ngày" type="date" />
        <Button style={{ alignSelf: "end" }}>Áp dụng kỳ báo cáo</Button>
        <p
          className="small muted"
          style={{ alignSelf: "end", paddingBottom: 8 }}
        >
          Mặc định 30 ngày gần nhất, tối đa 366 ngày.
        </p>
      </form>
      {error && <Notice tone="error">{error}</Notice>}
    </Panel>
  );
}
function Bars({
  items,
  title,
  unit = "booking",
}: {
  items: { name: string; value: number }[];
  title: string;
  unit?: string;
}) {
  const max = Math.max(1, ...items.map((x) => x.value));
  return (
    <Panel
      title={title}
      description={`Trục ngang: số ${unit} · Trục dọc: nhóm trạng thái`}
    >
      <div className="bar-chart">
        {items.map((x, i) => (
          <div className="bar-row" key={x.name + i}>
            <span>{x.name}</span>
            <div className="bar">
              <i style={{ width: `${(x.value / max) * 100}%` }} />
            </div>
            <strong>
              {x.value} {unit}
            </strong>
          </div>
        ))}
      </div>
      {!items.length && (
        <p className="muted small">Chưa có hoạt động trong kỳ.</p>
      )}
    </Panel>
  );
}
export function FacilityReport() {
  const { facility: f } = useFacility(),
    [query, setQuery] = useState(""),
    r = useResource<{ courts: CourtReport[] }>(
      `/bookings/reports/facility/${f.id}?${query}`,
    ),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`);
  const total = r.data?.courts.reduce((sum, x) => sum + Number(x.count), 0),
    value = r.data?.courts.reduce((sum, x) => sum + Number(x.booking_value), 0),
    minutes = r.data?.courts.reduce(
      (sum, x) => sum + Number(x.reserved_minutes),
      0,
    );
  const chart = r.data?.courts.reduce<Record<string, number>>(
    (acc, x) => ({
      ...acc,
      [x.status]: (acc[x.status] ?? 0) + Number(x.count),
    }),
    {},
  );
  return (
    <div className="stack">
      <RangeFilter onChange={setQuery} />
      <Notice>
        Lịch đặt tính theo giờ bắt đầu chơi. Giá trị lịch đặt là giá chốt lúc đặt,
        chưa phải doanh thu đã tất toán hoặc tiền chi trả cho chủ sân. API chưa
        cung cấp báo cáo giao dịch cơ sở, hoàn tiền và tỷ lệ khai thác đầy đủ.
      </Notice>
      <ResourceState resource={r}>
        <div className="grid-3">
          <Stat label="Lịch đặt trong kỳ" value={total ?? "—"} />
          <Stat
            label="Giá trị lịch đặt"
            value={value === undefined ? "—" : money(value)}
          />
          <Stat
            label="Thời gian trên lịch"
            value={minutes === undefined ? "—" : `${Math.round(minutes)} phút`}
          />
        </div>
        <Bars
          title="Lịch đặt theo trạng thái"
          items={Object.entries(chart ?? {}).map(([name, value]) => ({
            name: label(name),
            value,
          }))}
        />
        <Panel
          title="Chi tiết theo sân"
          action={
            <ExportButton
              rows={r.data?.courts ?? []}
              name="sporthub-facility-bookings"
            />
          }
        >
          <Table
            headers={[
              "Sân",
              "Trạng thái",
              "Số booking",
              "Giá trị lịch đặt",
              "Phút đặt sân",
            ]}
          >
            {r.data?.courts.map((x) => (
              <tr key={x.court_id + x.status}>
                <td>
                  {courts.data?.find((c) => c.id === x.court_id)?.name ??
                    x.court_id}
                </td>
                <td>{label(x.status)}</td>
                <td>{x.count}</td>
                <td>{money(Number(x.booking_value))}</td>
                <td>{Math.round(Number(x.reserved_minutes))}</td>
              </tr>
            ))}
          </Table>
        </Panel>
        <p className="muted small">
          Nguồn: Dịch vụ lịch đặt · Kỳ mặc định 30 ngày hoặc khoảng đã chọn · Giá
          trị gốc của booking, bao gồm các trạng thái trả về.
        </p>
      </ResourceState>
    </div>
  );
}
export function AdminReports({ dashboard = false }: { dashboard?: boolean }) {
  const [query, setQuery] = useState(""),
    users = useResource<{ status: string; total: number }[]>(
      "/admin/accounts/statistics",
    ),
    bookings = useResource<BookingStats>("/admin/bookings/statistics?" + query),
    payments = useResource<PaymentStats>("/admin/payments/statistics?" + query);
  const allUsers = users.data?.reduce((s, x) => s + Number(x.total), 0),
    allBookings = bookings.data?.states.reduce(
      (s, x) => s + Number(x.count),
      0,
    ),
    successful = payments.data?.orders
      .filter((x) => x.status === "SUCCESS")
      .reduce((s, x) => s + Number(x.amount), 0);
  return (
    <>
      <PageHead
        eyebrow="Thống kê"
        title={dashboard ? "Toàn cảnh SportHub" : "Báo cáo nền tảng"}
        description="Theo dõi tài khoản, lịch đặt và giao dịch từ từng nguồn nghiệp vụ."
      />
      <div className="stack">
        <RangeFilter onChange={setQuery} />
        <div className="grid-3">
          <Stat
            label="Tài khoản hiện tại"
            value={
              users.loading
                ? "Đang tải"
                : users.error
                  ? "Không tải được"
                  : (allUsers ?? "—")
            }
          />
          <Stat
            label="Lịch đặt trong kỳ"
            value={
              bookings.loading
                ? "Đang tải"
                : bookings.error
                  ? "Không tải được"
                  : (allBookings ?? "—")
            }
          />
          <Stat
            label="Giá trị giao dịch thành công"
            value={
              payments.loading
                ? "Đang tải"
                : payments.error
                  ? "Không tải được"
                  : successful === undefined
                    ? "—"
                    : money(successful)
            }
            detail="Bao gồm booking, đóng góp nhóm và chuyển nhượng."
          />
        </div>
        <ResourceState resource={bookings}>
          <Bars
            title="Lịch đặt theo trạng thái"
            items={
              bookings.data?.states.map((x) => ({
                name: label(x.status),
                value: Number(x.count),
              })) ?? []
            }
          />
        </ResourceState>
        <ResourceState resource={users}>
          <Panel title="Tài khoản theo trạng thái">
            <Table headers={["Trạng thái", "Tổng tài khoản"]}>
              {users.data?.map((x) => (
                <tr key={x.status}>
                  <td>{label(x.status)}</td>
                  <td>{x.total}</td>
                </tr>
              ))}
            </Table>
          </Panel>
        </ResourceState>
        <ResourceState resource={payments}>
          <Panel
            title="Giao dịch theo loại & trạng thái"
            action={
              <ExportButton
                rows={payments.data?.orders ?? []}
                name="sporthub-platform-payments"
              />
            }
          >
            <Table
              headers={["Loại giao dịch", "Trạng thái", "Số lượng", "Giá trị"]}
            >
              {payments.data?.orders.map((x) => (
                <tr key={x.purpose + x.status + x.currency}>
                  <td>
                    {x.purpose === "TRANSFER"
                      ? "Chuyển nhượng"
                      : x.purpose === "GROUP_CONTRIBUTION"
                        ? "Đóng góp nhóm"
                        : "Đặt sân"}
                  </td>
                  <td>{label(x.status)}</td>
                  <td>{x.count}</td>
                  <td>{money(Number(x.amount), x.currency)}</td>
                </tr>
              ))}
            </Table>
          </Panel>
          <div className="grid-2" style={{ marginTop: 24 }}>
            <Panel title="Tiền chuyển nhượng đang giữ">
              <Table headers={["Trạng thái", "Số giao dịch", "Số tiền"]}>
                {payments.data?.escrow.map((x) => (
                  <tr key={x.state + x.currency}>
                    <td>{label(x.state)}</td>
                    <td>{x.count}</td>
                    <td>{money(Number(x.amount), x.currency)}</td>
                  </tr>
                ))}
              </Table>
              {!payments.data?.escrow.length && (
                <p className="muted small">
                  Chưa có tiền chuyển nhượng đang giữ trong kỳ.
                </p>
              )}
            </Panel>
            <Panel title="Yêu cầu hoàn tiền cần xử lý">
              <Table headers={["Trạng thái", "Số yêu cầu"]}>
                {payments.data?.refundReviews.map((x) => (
                  <tr key={x.state}>
                    <td>{label(x.state)}</td>
                    <td>{x.count}</td>
                  </tr>
                ))}
              </Table>
              {!payments.data?.refundReviews.length && (
                <p className="muted small">Chưa có yêu cầu trong kỳ.</p>
              )}
            </Panel>
          </div>
        </ResourceState>
        <Notice>
          Giao dịch thành công không đồng nghĩa với doanh thu đã tất toán.
          Backend chưa thực thi chi trả escrow và hoàn tiền. Lịch đặt/giao dịch
          tính theo thời điểm tạo; tài khoản là số liệu hiện tại.
        </Notice>
        <p className="muted small">
          Nguồn: Tài khoản, lịch đặt và thanh toán · Kỳ mặc định 30 ngày hoặc
          khoảng đã chọn.
        </p>
      </div>
    </>
  );
}
