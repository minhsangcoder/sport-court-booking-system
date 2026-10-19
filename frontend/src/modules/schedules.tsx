"use client";
import Link from "next/link";
import { useState } from "react";
import { Copy, Plus, Trash2 } from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAction, useResource } from "@/rebuilt/lib/hooks";
import {
  type Category,
  type Court,
  type Hours,
  type Preview,
  type PriceRule,
  type ScheduleException,
  type Slot,
  label,
  money,
  time,
  today,
  weekdays,
} from "@/rebuilt/lib/domain";
import {
  Badge,
  Button,
  DetailList,
  Feedback,
  Field,
  Modal,
  Notice,
  Panel,
  ResourceState,
  Select,
  Table,
  TextArea,
} from "@/rebuilt/components/ui";
import { useFacility } from "./facilities";
export function SchedulePage() {
  const { facility: f, editable, base } = useFacility(),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    r = useResource<Hours[]>(`/schedules/facilities/${f.id}/hours`),
    exceptions = useResource<ScheduleException[]>(
      `/schedules/facilities/${f.id}/exceptions`,
    ),
    [scope, setScope] = useState(""),
    [type, setType] = useState("CLOSED"),
    [remove, setRemove] = useState<string | null>(null),
    action = useAction();
  return (
    <div className="stack">
      <Panel
        title="Giờ hoạt động thường kỳ"
        description="Một ngày có thể có nhiều khoảng mở cửa; lịch riêng của sân ghi đè lịch cơ sở."
        action={
          <Link href={base + "/schedule/preview"} className="text-link small">
            Xem trước lịch →
          </Link>
        }
      >
        <Select
          label="Phạm vi áp dụng"
          value={scope}
          onChange={(e) => setScope(e.target.value)}
        >
          <option value="">Toàn cơ sở</option>
          {courts.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
        <ResourceState resource={r}>
          {r.data && (
            <HoursEditor
              key={scope + "-" + JSON.stringify(r.data)}
              scope={scope}
              hours={r.data.filter((x) => (x.courtId ?? "") === scope)}
              facilityId={f.id}
              editable={editable}
              reload={r.reload}
            />
          )}
        </ResourceState>
      </Panel>
      <Panel
        title="Ngày nghỉ & lịch ngoại lệ"
        description="Lịch ngoại lệ được ưu tiên trước giờ hoạt động thường kỳ."
      >
        {editable && (
          <form
            style={{ marginBottom: 26 }}
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget,
                values = new FormData(form);
              const opensAt = values.get("opensAt"),
                closesAt = values.get("closesAt");
              void action.run(
                () => {
                  if (
                    type === "SPECIAL_HOURS" &&
                    String(opensAt) >= String(closesAt)
                  )
                    throw new Error("Giờ đóng cửa phải sau giờ mở cửa.");
                  return api(`/schedules/facilities/${f.id}/exceptions`, {
                    method: "POST",
                    body: JSON.stringify({
                      courtId: values.get("courtId") || null,
                      date: values.get("date"),
                      type,
                      opensAt: type === "SPECIAL_HOURS" ? opensAt : null,
                      closesAt: type === "SPECIAL_HOURS" ? closesAt : null,
                      reason: values.get("reason"),
                    }),
                  });
                },
                () => {
                  form.reset();
                  exceptions.reload();
                  action.setMessage("Đã thêm lịch ngoại lệ.");
                },
              );
            }}
          >
            <fieldset disabled={action.busy}>
              <div className="form-grid">
                <Select name="courtId" label="Phạm vi">
                  <option value="">Toàn cơ sở</option>
                  {courts.data?.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </Select>
                <Field
                  name="date"
                  label="Ngày áp dụng"
                  type="date"
                  required
                  min={today()}
                />
                <Select
                  label="Loại ngoại lệ"
                  value={type}
                  onChange={(e) => setType(e.target.value)}
                >
                  <option value="CLOSED">Đóng cửa / ngày nghỉ</option>
                  <option value="SPECIAL_HOURS">Mở cửa đặc biệt</option>
                </Select>
                {type === "SPECIAL_HOURS" && (
                  <>
                    <Field
                      name="opensAt"
                      label="Giờ mở cửa"
                      type="time"
                      required
                    />
                    <Field
                      name="closesAt"
                      label="Giờ đóng cửa"
                      type="time"
                      required
                    />
                  </>
                )}
                <TextArea
                  name="reason"
                  label="Lý do"
                  required
                  maxLength={500}
                />
              </div>
            </fieldset>
            <Feedback action={action} />
            <div className="form-footer">
              <p className="small muted">
                Kiểm tra booking đã xác nhận nếu thay đổi lịch phục vụ.
              </p>
              <Button busy={action.busy}>Thêm ngoại lệ</Button>
            </div>
          </form>
        )}
        <ResourceState
          resource={exceptions}
          empty={exceptions.data?.length === 0}
          emptyTitle="Chưa có ngày nghỉ hay ngoại lệ"
          emptyDescription="Sân đang sử dụng lịch hoạt động thường kỳ."
        >
          <Table
            headers={[
              "Ngày",
              "Phạm vi",
              "Loại & thời gian",
              "Lý do",
              "Thao tác",
            ]}
          >
            {exceptions.data?.map((x) => (
              <tr key={x.id}>
                <td>{x.date}</td>
                <td>
                  {courts.data?.find((c) => c.id === x.courtId)?.name ??
                    "Toàn cơ sở"}
                </td>
                <td>
                  <Badge state={x.type === "CLOSED" ? "CLOSED" : "ACTIVE"} />
                  {x.type === "SPECIAL_HOURS" && (
                    <small>
                      {x.opensAt} – {x.closesAt}
                    </small>
                  )}
                </td>
                <td style={{ whiteSpace: "normal" }}>{x.reason}</td>
                <td>
                  {editable && x.date >= today() && (
                    <Button variant="ghost" onClick={() => setRemove(x.id)}>
                      Hủy ngoại lệ
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
      <Modal
        open={!!remove}
        title="Hủy ngoại lệ này?"
        onClose={() => setRemove(null)}
      >
        <p className="small muted">
          Lịch sân sẽ quay về cấu hình giờ hoạt động thông thường.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Giữ ngoại lệ
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/schedules/facilities/${f.id}/exceptions/${remove}`, {
                    method: "DELETE",
                  }),
                () => {
                  setRemove(null);
                  exceptions.reload();
                },
              );
            }}
          >
            Hủy ngoại lệ
          </Button>
        </div>
      </Modal>
    </div>
  );
}
type Interval = Pick<
  Hours,
  "dayOfWeek" | "opensAt" | "closesAt" | "slotMinutes"
>;
function HoursEditor({
  scope,
  hours,
  facilityId,
  editable,
  reload,
}: {
  scope: string;
  hours: Hours[];
  facilityId: string;
  editable: boolean;
  reload: () => void;
}) {
  const [intervals, setIntervals] = useState<Interval[]>(
      hours.map(({ dayOfWeek, opensAt, closesAt, slotMinutes }) => ({
        dayOfWeek,
        opensAt: opensAt.slice(0, 5),
        closesAt: closesAt.slice(0, 5),
        slotMinutes,
      })),
    ),
    action = useAction(),
    [confirm, setConfirm] = useState(false);
  function update(i: number, patch: Partial<Interval>) {
    setIntervals(
      intervals.map((row, index) => (index === i ? { ...row, ...patch } : row)),
    );
  }
  function validate() {
    const mins = (v: string) =>
      Number(v.slice(0, 2)) * 60 + Number(v.slice(3, 5));
    for (const [i, row] of intervals.entries()) {
      if (
        row.opensAt >= row.closesAt ||
        (mins(row.closesAt) - mins(row.opensAt)) % row.slotMinutes !== 0
      )
        throw new Error(
          "Khoảng mở cửa phải chứa các khung giờ trọn vẹn và kết thúc sau khi mở cửa.",
        );
      if (
        intervals.some(
          (other, j) =>
            i !== j &&
            other.dayOfWeek === row.dayOfWeek &&
            row.opensAt < other.closesAt &&
            other.opensAt < row.closesAt,
        )
      )
        throw new Error(
          "Các khoảng mở cửa trong cùng một ngày không được chồng nhau.",
        );
    }
  }
  return (
    <form
      style={{ marginTop: 25 }}
      onSubmit={(e) => {
        e.preventDefault();
        try {
          validate();
          setConfirm(true);
        } catch (error) {
          action.setError((error as Error).message);
        }
      }}
    >
      <fieldset disabled={action.busy || !editable}>
        <div className="stack">
          {intervals.map((row, i) => (
            <div key={i} className="hours-row">
              <Select
                label="Ngày trong tuần"
                value={row.dayOfWeek}
                onChange={(e) =>
                  update(i, { dayOfWeek: Number(e.target.value) })
                }
              >
                {weekdays.map((d, index) => (
                  <option key={d} value={index + 1}>
                    {d}
                  </option>
                ))}
              </Select>
              <Field
                label="Mở cửa"
                type="time"
                required
                value={row.opensAt}
                onChange={(e) => update(i, { opensAt: e.target.value })}
              />
              <Field
                label="Đóng cửa"
                type="time"
                required
                value={row.closesAt}
                onChange={(e) => update(i, { closesAt: e.target.value })}
              />
              <Field
                label="Slot (phút)"
                type="number"
                min={5}
                max={720}
                required
                value={row.slotMinutes}
                onChange={(e) =>
                  update(i, { slotMinutes: Number(e.target.value) })
                }
              />
              <Button
                type="button"
                variant="ghost"
                aria-label={`Bỏ khoảng mở cửa ${weekdays[row.dayOfWeek - 1]}`}
                onClick={() =>
                  setIntervals(intervals.filter((_, index) => index !== i))
                }
              >
                <Trash2 size={16} />
              </Button>
            </div>
          ))}
        </div>
        {editable && (
          <div className="actions" style={{ marginTop: 20 }}>
            <Button
              type="button"
              variant="secondary"
              onClick={() =>
                setIntervals([
                  ...intervals,
                  {
                    dayOfWeek: 1,
                    opensAt: "06:00",
                    closesAt: "22:00",
                    slotMinutes: 60,
                  },
                ])
              }
            >
              <Plus size={15} />
              Thêm khoảng mở cửa
            </Button>
            {intervals.length === 1 && (
              <Button
                type="button"
                variant="ghost"
                onClick={() =>
                  setIntervals(
                    weekdays.map((_, i) => ({
                      ...intervals[0],
                      dayOfWeek: i + 1,
                    })),
                  )
                }
              >
                <Copy size={15} />
                Áp dụng cho cả tuần
              </Button>
            )}
            {scope && (
              <Button
                type="button"
                variant="ghost"
                onClick={() => setIntervals([])}
              >
                Kế thừa giờ cơ sở
              </Button>
            )}
          </div>
        )}
      </fieldset>
      {!intervals.length && (
        <Notice>
          {scope
            ? "Chưa có lịch riêng. Sân kế thừa giờ hoạt động của cơ sở."
            : "Chưa có giờ hoạt động. Sân không tạo khung giờ cho những ngày chưa cấu hình."}
        </Notice>
      )}
      <Feedback action={action} />
      {editable && (
        <div className="form-footer">
          <p className="small muted">Giờ hiển thị theo múi giờ cơ sở.</p>
          <Button disabled={!editable} busy={action.busy}>
            Lưu giờ hoạt động
          </Button>
        </div>
      )}
      <Modal
        open={confirm}
        title="Áp dụng giờ hoạt động mới?"
        onClose={() => setConfirm(false)}
      >
        <p className="small muted">
          Thay thế {hours.length} khoảng cũ bằng {intervals.length} khoảng mới
          trong phạm vi đã chọn. Lịch đặt đã xác nhận được giữ; bạn cần kiểm tra
          các lịch phục vụ bị ảnh hưởng.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button
            type="button"
            variant="secondary"
            onClick={() => setConfirm(false)}
          >
            Kiểm tra lại
          </Button>
          <Button
            type="button"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/schedules/facilities/${facilityId}/hours`, {
                    method: "PUT",
                    body: JSON.stringify({ courtId: scope || null, intervals }),
                  }),
                () => {
                  setConfirm(false);
                  reload();
                },
              );
            }}
          >
            Áp dụng cấu hình
          </Button>
        </div>
      </Modal>
    </form>
  );
}
export function PricingPage() {
  const { facility: f, base, editable } = useFacility(),
    r = useResource<PriceRule[]>(`/schedules/facilities/${f.id}/pricing`),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    categories = useResource<Category[]>("/sport-categories"),
    action = useAction(),
    [scope, setScope] = useState("facility"),
    [dayType, setDayType] = useState("weekday"),
    [copy, setCopy] = useState<PriceRule | null>(null),
    [remove, setRemove] = useState<string | null>(null);
  return (
    <div className="stack">
      {editable && (
        <Panel
          title={copy ? "Tạo quy tắc từ bản sao" : "Thiết lập quy tắc giá"}
          description="Giá theo khung giờ, có thời hạn và mức ưu tiên rõ ràng."
          action={
            <Link className="text-link small" href={base + "/schedule/preview"}>
              Xem trước giá →
            </Link>
          }
        >
          <form
            key={copy?.id ?? "new"}
            onSubmit={(e) => {
              e.preventDefault();
              const values = new FormData(e.currentTarget),
                form = e.currentTarget;
              void action.run(
                () => {
                  const v = (k: string) => String(values.get(k) ?? "");
                  if (v("startsAt") >= v("endsAt"))
                    throw new Error("Giờ kết thúc phải sau giờ bắt đầu.");
                  if (v("effectiveTo") && v("effectiveTo") < v("effectiveFrom"))
                    throw new Error(
                      "Ngày kết thúc hiệu lực phải từ ngày bắt đầu trở đi.",
                    );
                  const body = {
                    courtId: scope === "court" ? v("courtId") : null,
                    sportCategoryId:
                      scope === "sport" ? v("sportCategoryId") : null,
                    dayOfWeek:
                      dayType === "weekday" ? Number(v("dayOfWeek")) : null,
                    specificDate:
                      dayType === "specific" ? v("specificDate") : null,
                    startsAt: v("startsAt"),
                    endsAt: v("endsAt"),
                    pricePerSlot: Number(v("pricePerSlot")),
                    priority: Number(v("priority")),
                    label: v("label"),
                    effectiveFrom: v("effectiveFrom"),
                    effectiveTo: v("effectiveTo") || null,
                    currency: "VND",
                  };
                  return api(`/schedules/facilities/${f.id}/pricing`, {
                    method: "POST",
                    body: JSON.stringify(body),
                  });
                },
                () => {
                  form.reset();
                  setCopy(null);
                  r.reload();
                  action.setMessage(
                    "Đã tạo quy tắc giá mới. Giá của booking cũ được giữ nguyên.",
                  );
                },
              );
            }}
          >
            <fieldset disabled={action.busy}>
              <div className="form-grid">
                <Field
                  name="label"
                  label="Tên quy tắc"
                  required
                  maxLength={200}
                  defaultValue={copy?.label}
                  error={action.fields.label}
                />
                <Select
                  label="Phạm vi giá"
                  value={scope}
                  onChange={(e) => setScope(e.target.value)}
                >
                  <option value="facility">Toàn cơ sở</option>
                  <option value="court">Sân cụ thể</option>
                  <option value="sport">Theo bộ môn</option>
                </Select>
                {scope === "court" && (
                  <Select
                    name="courtId"
                    label="Sân áp dụng"
                    required
                    defaultValue={copy?.courtId ?? ""}
                  >
                    <option value="">Chọn sân</option>
                    {courts.data?.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </Select>
                )}
                {scope === "sport" && (
                  <Select
                    name="sportCategoryId"
                    label="Bộ môn áp dụng"
                    required
                    defaultValue={copy?.sportCategoryId ?? ""}
                  >
                    <option value="">Chọn bộ môn</option>
                    {categories.data
                      ?.filter((c) => c.active)
                      .map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                  </Select>
                )}
                <Select
                  label="Áp dụng theo"
                  value={dayType}
                  onChange={(e) => setDayType(e.target.value)}
                >
                  <option value="weekday">Ngày trong tuần</option>
                  <option value="specific">Ngày đặc biệt</option>
                </Select>
                {dayType === "weekday" ? (
                  <Select
                    name="dayOfWeek"
                    label="Ngày trong tuần"
                    defaultValue={copy?.dayOfWeek ?? 1}
                  >
                    {weekdays.map((d, i) => (
                      <option key={d} value={i + 1}>
                        {d}
                      </option>
                    ))}
                  </Select>
                ) : (
                  <Field
                    name="specificDate"
                    label="Ngày đặc biệt"
                    type="date"
                    required
                    defaultValue={copy?.specificDate ?? ""}
                  />
                )}
                <Field
                  name="startsAt"
                  label="Từ giờ"
                  type="time"
                  required
                  defaultValue={copy?.startsAt.slice(0, 5)}
                />
                <Field
                  name="endsAt"
                  label="Đến giờ"
                  type="time"
                  required
                  defaultValue={copy?.endsAt.slice(0, 5)}
                />
                <Field
                  name="pricePerSlot"
                  label="Giá mỗi khung giờ (VND)"
                  type="number"
                  min={1}
                  max={9999999999}
                  step={1}
                  required
                  defaultValue={copy?.pricePerSlot}
                  error={action.fields.pricePerSlot}
                />
                <Field
                  name="priority"
                  label="Mức ưu tiên"
                  type="number"
                  min={0}
                  max={10000}
                  required
                  defaultValue={copy?.priority ?? 0}
                  hint="Server ưu tiên phạm vi cụ thể, ngày đặc biệt và mức ưu tiên."
                />
                <Field
                  name="effectiveFrom"
                  label="Hiệu lực từ ngày"
                  type="date"
                  required
                  defaultValue={copy?.effectiveFrom ?? today()}
                />
                <Field
                  name="effectiveTo"
                  label="Hiệu lực đến ngày (tùy chọn)"
                  type="date"
                  defaultValue={copy?.effectiveTo ?? ""}
                />
              </div>
            </fieldset>
            <Feedback action={action} />
            <div className="form-footer">
              <p className="muted small">
                Giá được kiểm tra theo giờ hoạt động và xung đột quy tắc.
              </p>
              <div className="actions">
                {copy && (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={() => setCopy(null)}
                  >
                    Bỏ bản sao
                  </Button>
                )}
                <Button busy={action.busy}>Thêm quy tắc giá</Button>
              </div>
            </div>
          </form>
        </Panel>
      )}
      <Panel title="Bảng giá đang quản lý">
        <ResourceState
          resource={r}
          empty={r.data?.length === 0}
          emptyTitle="Chưa có quy tắc giá"
          emptyDescription="Thiết lập giá theo ngày và khung giờ để sân có thể nhận booking."
        >
          <Table
            headers={[
              "Quy tắc / Phạm vi",
              "Ngày & khung giờ",
              "Giá / khung giờ",
              "Hiệu lực",
              "Trạng thái",
              "Thao tác",
            ]}
          >
            {r.data?.map((x) => (
              <tr key={x.id}>
                <td>
                  <strong>{x.label}</strong>
                  <small>
                    {x.courtId
                      ? courts.data?.find((c) => c.id === x.courtId)?.name
                      : x.sportCategoryId
                        ? categories.data?.find(
                            (c) => c.id === x.sportCategoryId,
                          )?.name
                        : "Toàn cơ sở"}
                  </small>
                  <small>
                    Ưu tiên {x.priority} · Phiên bản {x.version}
                  </small>
                </td>
                <td>
                  {x.specificDate ?? weekdays[(x.dayOfWeek ?? 1) - 1]}
                  <small>
                    {x.startsAt} – {x.endsAt}
                  </small>
                </td>
                <td>{money(x.pricePerSlot, x.currency)}</td>
                <td>
                  {x.effectiveFrom}
                  <small>đến {x.effectiveTo ?? "Không giới hạn"}</small>
                </td>
                <td>
                  <Badge state={x.active ? "ACTIVE" : "DISABLED"} />
                </td>
                <td>
                  {editable && (
                    <div className="actions">
                      <Button
                        variant="ghost"
                        onClick={() => {
                          setCopy(x);
                          setScope(
                            x.courtId
                              ? "court"
                              : x.sportCategoryId
                                ? "sport"
                                : "facility",
                          );
                          setDayType(x.specificDate ? "specific" : "weekday");
                        }}
                      >
                        Sao chép
                      </Button>
                      {x.active && (
                        <Button variant="ghost" onClick={() => setRemove(x.id)}>
                          Ngừng hiệu lực
                        </Button>
                      )}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        </ResourceState>
      </Panel>
      <Modal
        open={!!remove}
        title="Ngừng quy tắc giá?"
        onClose={() => setRemove(null)}
      >
        <p className="small muted">
          Các booking đã tạo giữ nguyên giá chốt. Slot mới sẽ tính theo những
          quy tắc còn hiệu lực.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setRemove(null)}>
            Giữ quy tắc
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/schedules/facilities/${f.id}/pricing/${remove}`, {
                    method: "DELETE",
                  }),
                () => {
                  setRemove(null);
                  r.reload();
                },
              );
            }}
          >
            Ngừng hiệu lực
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function SchedulePreview() {
  const { facility: f } = useFacility(),
    courts = useResource<Court[]>(`/owner/facilities/${f.id}/courts`),
    [selected, setSelected] = useState(""),
    [date, setDate] = useState(today()),
    [filter, setFilter] = useState("all"),
    [slot, setSlot] = useState<Slot | null>(null),
    court = selected || courts.data?.[0]?.id,
    r = useResource<Preview>(
      court
        ? `/schedules/facilities/${f.id}/preview?courtId=${court}&date=${date}`
        : null,
    );
  return (
    <Panel
      title="Xem trước lịch & giá"
      description="Kiểm tra cấu hình theo múi giờ cơ sở. Chức năng này không giữ chỗ hoặc tạo booking."
    >
      <div className="form-grid">
        <Select
          label="Sân"
          value={court ?? ""}
          onChange={(e) => setSelected(e.target.value)}
        >
          <option value="" disabled>
            Chọn sân
          </option>
          {courts.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
        <Field
          label="Ngày xem trước"
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
        <Select
          label="Trạng thái khung giờ"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        >
          <option value="all">Tất cả</option>
          <option value="ELIGIBLE">Có thể cung cấp</option>
          <option value="blocked">Đang khóa</option>
        </Select>
      </div>
      <Notice>
        Đây là lịch từ cấu hình Schedule. Xem lịch vận hành để đối chiếu booking
        và hold thực tế.
      </Notice>
      {court && (
        <ResourceState
          resource={r}
          empty={r.data?.slots.length === 0}
          emptyTitle="Chưa có khung giờ cấu hình"
          emptyDescription="Kiểm tra giờ mở cửa và sân đã được kích hoạt."
        >
          <div className="slot-grid">
            {r.data?.slots
              .filter(
                (s) =>
                  filter === "all" ||
                  (filter === "ELIGIBLE" && s.state === "ELIGIBLE") ||
                  (filter === "blocked" && s.state !== "ELIGIBLE"),
              )
              .map((s) => (
                <button
                  type="button"
                  className="slot"
                  key={s.startsAt}
                  style={
                    s.state !== "ELIGIBLE"
                      ? { background: "#f2f3ed", color: "#7e897a" }
                      : undefined
                  }
                  onClick={() => setSlot(s)}
                >
                  <strong>
                    {time(s.startsAt, f.timezone)} –{" "}
                    {time(s.endsAt, f.timezone)}
                  </strong>
                  <small>
                    {s.amount === null
                      ? label(s.state)
                      : money(s.amount, s.currency)}
                  </small>
                </button>
              ))}
          </div>
        </ResourceState>
      )}
      <Modal
        open={!!slot}
        title="Chi tiết khung giờ cấu hình"
        onClose={() => setSlot(null)}
      >
        {slot && (
          <DetailList
            items={[
              [
                "Khung giờ",
                `${time(slot.startsAt, f.timezone)} – ${time(slot.endsAt, f.timezone)}`,
              ],
              ["Trạng thái", label(slot.state)],
              [
                "Giá",
                slot.amount === null
                  ? "Chưa có giá"
                  : money(slot.amount, slot.currency),
              ],
              ["Quy tắc giá", slot.ruleLabel ?? "Không có quy tắc"],
              ["Phiên bản", slot.ruleVersion],
              ["Lý do", slot.reason ?? "Không có"],
            ]}
          />
        )}
      </Modal>
    </Panel>
  );
}
