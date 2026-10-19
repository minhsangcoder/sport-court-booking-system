"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ArrowRight, ArrowUpRight, MapPin, Repeat2 } from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import {
  useAction,
  useCountdown,
  useIdempotency,
  useResource,
} from "@/rebuilt/lib/hooks";
import {
  type Acquisition,
  type BookingDetail,
  type Category,
  type Listing,
  type Payment,
  dateTime,
  localInstant,
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
  Select,
  Tabs,
} from "@/rebuilt/components/ui";
export function ListingCard({ listing: l }: { listing: Listing }) {
  const saving = l.originalAmount - l.price;
  return (
    <Link className="panel" href={`/transfers/${l.id}`}>
      <div
        className="actions"
        style={{ justifyContent: "space-between", marginBottom: 19 }}
      >
        <span className="sport-icon">
          <Repeat2 size={22} />
        </span>
        <Badge state={l.state} />
      </div>
      <h3>{l.venue.facilityName}</h3>
      <p className="venue-location">
        <MapPin size={13} />
        {l.venue.district}, {l.venue.province}
      </p>
      <p className="small" style={{ fontWeight: 600 }}>
        {l.venue.courtName}
      </p>
      <p className="small muted" style={{ marginTop: 8 }}>
        {dateTime(l.startsAt, l.venue.timezone)} –{" "}
        {time(l.endsAt, l.venue.timezone)}
      </p>
      <div className="venue-card-bottom" style={{ marginTop: 20 }}>
        <div>
          <strong>{money(l.price, l.currency)}</strong>{" "}
          <small>
            <s>{money(l.originalAmount, l.currency)}</s>
          </small>
          {saving > 0 && (
            <p className="small" style={{ color: "#557d32" }}>
              Tiết kiệm {money(saving, l.currency)}
            </p>
          )}
        </div>
        <ArrowUpRight size={22} />
      </div>
    </Link>
  );
}
export function TransferMarket({ mine = false }: { mine?: boolean }) {
  const params = useSearchParams(),
    router = useRouter(),
    [tab, setTab] = useState("listings");
  const query = params.toString(),
    r = useResource<Listing[]>(
      mine ? "/transfers/mine" : "/transfers?" + query,
    ),
    purchases = useResource<Acquisition[]>(
      mine ? "/transfers/acquisitions" : null,
    ),
    categories = useResource<Category[]>("/sport-categories");
  return (
    <div className="container">
      <PageHead
        eyebrow={mine ? "Chuyển nhượng của bạn" : "Chuyển nhượng lịch đặt"}
        title={
          mine ? "Chuyển nhượng của bạn" : "Một lịch chơi mới. Một mức giá tốt."
        }
        description={
          mine
            ? "Theo dõi bài đăng và các lịch chơi bạn đã nhận lại."
            : "Nhận lại booking đã thanh toán từ người chơi khác, theo điều kiện của cơ sở."
        }
        action={
          mine ? (
            <LinkButton href="/transfers" variant="secondary" arrow>
              Khám phá chợ
            </LinkButton>
          ) : (
            <LinkButton href="/customer/transfers" variant="secondary">
              Bài đăng của tôi
            </LinkButton>
          )
        }
      />
      {mine ? (
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            ["listings", "Bài đăng của tôi"],
            ["purchases", "Booking đã nhận"],
          ]}
        />
      ) : (
        <Panel title="Tìm lịch chơi phù hợp">
          <form
            key={query}
            className="form-grid"
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              const p = new URLSearchParams();
              for (const k of ["q", "category", "maxPrice", "sort"])
                if (f.get(k)) p.set(k, String(f.get(k)));
              for (const k of ["after", "before"])
                if (f.get(k)) p.set(k, localInstant(String(f.get(k))));
              router.push("/transfers?" + p);
            }}
          >
            <Field
              label="Tên hoặc khu vực"
              name="q"
              maxLength={180}
              defaultValue={params.get("q") ?? ""}
            />
            <Select
              name="category"
              label="Bộ môn"
              defaultValue={params.get("category") ?? ""}
            >
              <option value="">Tất cả bộ môn</option>
              {categories.data
                ?.filter((c) => c.active)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </Select>
            <Field
              label="Từ thời điểm (giờ Việt Nam)"
              name="after"
              type="datetime-local"
            />
            <Field
              label="Đến thời điểm (giờ Việt Nam)"
              name="before"
              type="datetime-local"
            />
            <Field
              label="Giá tối đa (VND)"
              name="maxPrice"
              type="number"
              min={1}
              defaultValue={params.get("maxPrice") ?? ""}
            />
            <Select
              name="sort"
              label="Sắp xếp"
              defaultValue={params.get("sort") ?? "start"}
            >
              <option value="start">Giờ chơi gần nhất</option>
              <option value="price">Giá thấp trước</option>
            </Select>
            <div className="span-2 actions">
              <Button>Tìm lịch chuyển nhượng</Button>
              <Link className="text-link small" href="/transfers">
                Xóa bộ lọc
              </Link>
            </div>
          </form>
        </Panel>
      )}
      <div style={{ marginTop: 28 }}>
        {mine && tab === "purchases" ? (
          <ResourceState
            resource={purchases}
            empty={purchases.data?.length === 0}
            emptyTitle="Chưa nhận chuyển nhượng"
            emptyDescription="Các giao dịch nhận booking của bạn sẽ xuất hiện ở đây."
          >
            <div className="grid-3">
              {purchases.data?.map((a) => (
                <Panel key={a.id}>
                  <Badge state={a.state} />
                  <h3 style={{ margin: "16px 0" }}>
                    Giao dịch #{shortId(a.id)}
                  </h3>
                  <p style={{ fontSize: 22, fontWeight: 600 }}>
                    {money(a.amount, a.currency)}
                  </p>
                  <LinkButton
                    href={`/customer/transfers/acquisitions/${a.id}`}
                    variant="secondary"
                    arrow
                  >
                    Xem giao dịch
                  </LinkButton>
                </Panel>
              ))}
            </div>
          </ResourceState>
        ) : (
          <ResourceState
            resource={r}
            empty={r.data?.length === 0}
            emptyTitle={
              mine
                ? "Bạn chưa đăng chuyển nhượng"
                : "Chưa có lịch chuyển nhượng phù hợp"
            }
            emptyDescription={
              mine
                ? "Mở booking đã thanh toán và chọn đăng chuyển nhượng nếu đủ điều kiện."
                : "Thử đổi bộ lọc hoặc quay lại khi có lịch chơi mới."
            }
          >
            <div className="grid-3">
              {r.data?.map((l) => (
                <ListingCard key={l.id} listing={l} />
              ))}
            </div>
          </ResourceState>
        )}
      </div>
    </div>
  );
}
export function ListingPage({ id }: { id: string }) {
  const r = useResource<Listing>(`/transfers/${id}`),
    action = useAction(),
    key = useIdempotency(),
    router = useRouter(),
    [withdraw, setWithdraw] = useState(false);
  const policy = useResource<{
    configured: boolean;
    enabled: boolean;
    minLeadSeconds: number;
  }>(r.data ? `/facilities/${r.data.facilityId}/transfer-policy` : null);
  return (
    <div className="container narrow">
      <ResourceState resource={r}>
        {r.data && (
          <>
            <PageHead
              eyebrow="Lịch sân được chuyển nhượng"
              title={r.data.venue.facilityName}
              description={r.data.venue.address}
              action={<Badge state={r.data.state} />}
            />
            <div className="grid-2">
              <Panel title={r.data.venue.courtName}>
                <DetailList
                  items={[
                    [
                      "Ngày chơi",
                      dateTime(r.data.startsAt, r.data.venue.timezone),
                    ],
                    [
                      "Khung giờ",
                      `${time(r.data.startsAt, r.data.venue.timezone)} – ${time(r.data.endsAt, r.data.venue.timezone)}`,
                    ],
                    ["Giá booking gốc", money(r.data.originalAmount)],
                    [
                      "Giá chuyển nhượng",
                      <strong
                        key="price"
                        style={{ fontSize: 24, color: "var(--brand)" }}
                      >
                        {money(r.data.price)}
                      </strong>,
                    ],
                    ["Tiết kiệm", money(r.data.originalAmount - r.data.price)],
                    ["Hạn bài đăng", dateTime(r.data.deadline)],
                  ]}
                />
                <Link
                  className="text-link small"
                  href={`/facilities/${r.data.facilityId}`}
                >
                  Xem cơ sở & tiện ích{" "}
                  <ArrowUpRight size={13} style={{ display: "inline" }} />
                </Link>
              </Panel>
              <Panel
                title={
                  r.data.own ? "Quản lý bài đăng" : "Sẵn sàng nhận lịch chơi?"
                }
              >
                <p className="muted small">
                  Quyền sử dụng sân được chuyển khi thanh toán đã xác minh và hệ
                  thống hoàn tất bàn giao. Mã check-in mới sẽ xuất hiện trong
                  booking của bạn.
                </p>
                {policy.data && (
                  <Notice>
                    {policy.data.configured && policy.data.enabled
                      ? `Cơ sở cho phép nhận chuyển nhượng trước giờ chơi tối thiểu ${Math.ceil(policy.data.minLeadSeconds / 60)} phút.`
                      : "Cơ sở hiện chưa mở chuyển nhượng."}
                  </Notice>
                )}
                <Feedback action={action} />
                <div className="stack">
                  {r.data.own ? (
                    r.data.state === "ACTIVE" ? (
                      <>
                        <LinkButton
                          href={`/customer/transfers/${id}/edit`}
                          variant="secondary"
                        >
                          Chỉnh sửa giá & thời hạn
                        </LinkButton>
                        <Button
                          variant="ghost"
                          onClick={() => setWithdraw(true)}
                        >
                          Rút bài đăng
                        </Button>
                      </>
                    ) : (
                      <p className="small muted">
                        Bài đăng đang xử lý hoặc đã kết thúc, không thể chỉnh
                        sửa.
                      </p>
                    )
                  ) : r.data.available ? (
                    <Button
                      busy={action.busy}
                      onClick={() => {
                        void action.run(
                          () =>
                            api<Acquisition>(`/transfers/${id}/acquisitions`, {
                              method: "POST",
                              headers: {
                                "Idempotency-Key": key("acquire:" + id),
                              },
                            }),
                          (a) =>
                            router.push(
                              `/customer/transfers/acquisitions/${a.id}`,
                            ),
                        );
                      }}
                    >
                      Nhận lịch chơi này <ArrowRight size={16} />
                    </Button>
                  ) : (
                    <Notice tone="warning">
                      Lịch này không còn khả dụng hoặc đang có người thanh toán.
                    </Notice>
                  )}
                </div>
                <Notice tone="warning">
                  Backend chưa thực thi chi trả tiền chuyển nhượng cho người
                  bán. Giao diện không xác nhận tiền đã được chi trả.
                </Notice>
              </Panel>
            </div>
          </>
        )}
      </ResourceState>
      <Modal
        open={withdraw}
        title="Rút bài đăng chuyển nhượng?"
        onClose={() => setWithdraw(false)}
      >
        <p className="muted small">
          Bài đăng sẽ rời khỏi chợ. Quyền sử dụng booking gốc vẫn thuộc về bạn.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setWithdraw(false)}>
            Giữ bài đăng
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () => api(`/transfers/${id}/withdraw`, { method: "POST" }),
                () => {
                  setWithdraw(false);
                  r.reload();
                },
              );
            }}
          >
            Xác nhận rút tin
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function ListingForm({
  bookingId,
  listingId,
}: {
  bookingId?: string;
  listingId?: string;
}) {
  const booking = useResource<BookingDetail>(
      bookingId ? `/bookings/${bookingId}` : null,
    ),
    listing = useResource<Listing>(
      listingId ? `/transfers/${listingId}` : null,
    ),
    action = useAction(),
    key = useIdempotency(),
    router = useRouter(),
    r = bookingId ? booking : listing;
  const original = booking.data?.booking.amount ?? listing.data?.originalAmount,
    start = booking.data?.booking.startsAt ?? listing.data?.startsAt;
  return (
    <div className="container narrow">
      <PageHead
        eyebrow="Chuyển nhượng lịch đặt"
        title={
          listingId
            ? "Điều chỉnh lịch chuyển nhượng"
            : "Nhường lịch. Tiếp nối cuộc chơi."
        }
        description="Giá chuyển nhượng không vượt quá số tiền booking đã thanh toán."
      />
      <ResourceState resource={r}>
        <Panel title="Giá & thời hạn">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              void action.run(async () => {
                const body = {
                  price: Number(f.get("price")),
                  deadline: localInstant(String(f.get("deadline"))),
                };
                if (start && new Date(body.deadline) > new Date(start))
                  throw new Error("Hạn bài đăng không được vượt quá giờ chơi.");
                const l = await api<Listing>(
                  listingId ? `/transfers/${listingId}` : "/transfers",
                  {
                    method: listingId ? "PUT" : "POST",
                    headers: listingId
                      ? {}
                      : {
                          "Idempotency-Key": key(
                            JSON.stringify({ bookingId, ...body }),
                          ),
                        },
                    body: JSON.stringify(
                      listingId ? body : { bookingId, ...body },
                    ),
                  },
                );
                router.push(`/transfers/${l.id}`);
              });
            }}
          >
            <fieldset
              disabled={
                action.busy ||
                (!!listingId &&
                  (!listing.data?.own || listing.data.state !== "ACTIVE"))
              }
              className="stack"
            >
              <Field
                name="price"
                label="Giá chuyển nhượng (VND)"
                type="number"
                min={1}
                max={original}
                step={1}
                required
                defaultValue={listing.data?.price ?? original}
                hint={`Giá booking gốc: ${original ? money(original) : "Đang tải"}`}
              />
              <Field
                name="deadline"
                label="Thời hạn bài đăng (giờ Việt Nam)"
                type="datetime-local"
                required
                defaultValue={
                  listing.data
                    ? new Date(
                        new Date(listing.data.deadline).getTime() + 7 * 3600000,
                      )
                        .toISOString()
                        .slice(0, 16)
                    : undefined
                }
              />
              <Notice>
                Booking phải đã thanh toán, còn hiệu lực và đáp ứng chính sách
                cơ sở. Backend kiểm tra lại điều kiện khi lưu. Booking nhóm đã
                thanh toán chưa hỗ trợ chuyển nhượng.
              </Notice>
              <Button busy={action.busy}>
                {listingId ? "Lưu thay đổi" : "Đăng chuyển nhượng"}
              </Button>
            </fieldset>
            <Feedback action={action} />
          </form>
        </Panel>
      </ResourceState>
    </div>
  );
}
export function AcquisitionPage({ id }: { id: string }) {
  const r = useResource<Acquisition>(`/transfers/acquisitions/${id}`);
  useEffect(() => {
    if (!r.data || !["PENDING_HANDOFF", "PENDING_AUDIT"].includes(r.data.state))
      return;
    const timer = setInterval(r.reload, 5000);
    return () => clearInterval(timer);
  }, [r.data, r.reload]);
  return (
    <div className="container narrow">
      <ResourceState resource={r}>
        {r.data && (
          <AcquisitionDetails acquisition={r.data} reload={r.reload} />
        )}
      </ResourceState>
    </div>
  );
}
function AcquisitionDetails({
  acquisition: a,
  reload,
}: {
  acquisition: Acquisition;
  reload: () => void;
}) {
  const action = useAction(),
    key = useIdempotency(),
    router = useRouter(),
    countdown = useCountdown(a.expiresAt);
  const [cancel, setCancel] = useState(false);
  return (
    <>
      <PageHead
        eyebrow="Giao dịch của bạn"
        title={`Giao dịch #${shortId(a.id)}`}
        description="Theo dõi thanh toán và trạng thái bàn giao quyền sử dụng."
      />
      <Panel title="Thông tin nhận chuyển nhượng">
        <Badge state={a.state} />
        <p className="payment-total">{money(a.amount, a.currency)}</p>
        <DetailList
          items={[
            ["Hạn thanh toán", dateTime(a.expiresAt)],
            ["Thời gian còn lại", countdown.text],
            ["Lịch đặt", shortId(a.bookingId)],
          ]}
        />
        <Feedback action={action} />
        <div className="actions">
          {a.state === "PENDING" && !countdown.expired && (
            <>
              <Button
                busy={action.busy}
                onClick={() => {
                  void action.run(
                    () =>
                      api<Payment>("/payments", {
                        method: "POST",
                        headers: { "Idempotency-Key": key("payment:" + a.id) },
                        body: JSON.stringify({
                          bookingId: a.bookingId,
                          acquisitionId: a.id,
                        }),
                      }),
                    (p) => router.push(`/customer/payments/${p.id}`),
                  );
                }}
              >
                Tiếp tục thanh toán
              </Button>
              <Button variant="ghost" onClick={() => setCancel(true)}>
                Hủy giao dịch
              </Button>
            </>
          )}
          {a.state === "SUCCESS" && (
            <LinkButton href={`/customer/bookings/${a.bookingId}`} arrow>
              Mở vé chơi của bạn
            </LinkButton>
          )}
          {a.paymentId && (
            <LinkButton
              href={`/customer/payments/${a.paymentId}`}
              variant="secondary"
            >
              Xem thanh toán
            </LinkButton>
          )}
          <Button variant="ghost" onClick={reload}>
            Cập nhật trạng thái
          </Button>
        </div>
        {["PENDING_HANDOFF", "PENDING_AUDIT"].includes(a.state) && (
          <Notice>
            Giao dịch đang được đối soát hoặc bàn giao. Quyền sử dụng chỉ được
            xác nhận khi trạng thái là thành công.
          </Notice>
        )}
      </Panel>
      <Modal
        open={cancel}
        title="Hủy nhận chuyển nhượng?"
        onClose={() => setCancel(false)}
      >
        <p className="muted small">
          Khóa tạm sẽ được giải phóng để người khác có thể nhận booking.
        </p>
        <Feedback action={action} />
        <div className="form-footer">
          <Button variant="secondary" onClick={() => setCancel(false)}>
            Quay lại
          </Button>
          <Button
            variant="danger"
            busy={action.busy}
            onClick={() => {
              void action.run(
                () =>
                  api(`/transfers/acquisitions/${a.id}/cancel`, {
                    method: "POST",
                  }),
                () => {
                  setCancel(false);
                  reload();
                },
              );
            }}
          >
            Xác nhận hủy
          </Button>
        </div>
      </Modal>
    </>
  );
}
