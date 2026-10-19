"use client";
import { Photo } from "@/rebuilt/components/photo";
import { QuoteBreakdown } from "@/rebuilt/components/quote-breakdown";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Building2,
  Check,
  Clock3,
  MapPin,
  Search,
  SlidersHorizontal,
} from "lucide-react";
import { api } from "@/rebuilt/lib/api";
import { useAuth } from "@/rebuilt/lib/auth-context";
import {
  useAction,
  useIdempotency,
  useResource,
  useCurrentTime,
  formQuery,
} from "@/rebuilt/lib/hooks";
import {
  type Category,
  type Court,
  type Facility,
  type Hold,
  type Media,
  type Preview,
  type Quote,
  type FacilitySearchResult,
  type FacilityDiscoveryItem,
  money,
  today,
  time,
  label,
} from "@/rebuilt/lib/domain";
import { discoveryQuery, facilitySearchHref, courtSearchHref } from "@/rebuilt/lib/discovery";
import { selectedInterval } from "@/rebuilt/lib/selection";
import { provinces, provinceByCode, provinceByName } from "@/features/facility/administrative-divisions";
import {
  Badge,
  Button,
  Empty,
  Feedback,
  Field,
  LinkButton,
  Notice,
  PageHead,
  Panel,
  ResourceState,
  Select,
  Stepper,
  Tabs,
} from "@/rebuilt/components/ui";
import { CourtMap } from "@/rebuilt/components/map";
export function VenueCard({ item, query = new URLSearchParams() }: { item: FacilityDiscoveryItem; query?: URLSearchParams }) {
  const [failed, setFailed] = useState(false);
  return (
    <Link className="venue-card" href={facilitySearchHref(item, query)}>
      <div className="venue-cover">
        {item.imageUrl && !failed ? (
          <Photo src={item.imageUrl} alt={item.facility.name} onError={() => setFailed(true)} loading="lazy" />
        ) : <Building2 aria-label="Cơ sở chưa có ảnh" />}
        <Badge>{item.sports.map((sport) => sport.name).join(" · ")}</Badge>
      </div>
      <div className="venue-card-body">
        <h3>{item.facility.name}</h3>
        <p className="venue-location"><MapPin size={13} />{[item.facility.addressLine, item.facility.ward, item.facility.province].filter(Boolean).join(", ")}</p>
        <div className="venue-meta">
          <span>{item.matchingCourts} sân phù hợp</span>
          {item.distanceKm !== null && <span>{item.distanceKm.toFixed(1)} km</span>}
        </div>
        <div className="venue-card-bottom">
          <div><small>Từ </small><strong>{money(item.fromPrice, item.currency)}</strong><small> / khung giờ</small></div>
          <span className="venue-card-cta">Xem sân trống <ArrowRight size={16} /></span>
        </div>
      </div>
    </Link>
  );
}
export function Home() {
  const router = useRouter(),
    categories = useResource<Category[]>("/sport-categories"),
    results = useResource<FacilitySearchResult>(
      `/bookings/search/facilities?date=${today()}&sort=NAME&size=3`,
    );
  return (
    <>
      <section className="hero">
        <div className="hero-grid">
          <div className="hero-copy">
            <p className="eyebrow">Đặt sân thể thao</p>
            <h1>
              Đến sân.
              <br />
              <span>Vào cuộc.</span>
            </h1>
            <p>
              Một chiếc sân ưng ý. Một khung giờ vừa vặn. Những cuộc chơi đáng
              mong chờ.
            </p>
            <div className="actions">
              <LinkButton href="/search" arrow>
                Khám phá sân ngay
              </LinkButton>
              <Link href="/customer/groups" className="text-link small">
                Rủ nhóm cùng chơi{" "}
                <ArrowRight
                  size={13}
                  style={{ display: "inline", marginLeft: 5 }}
                />
              </Link>
            </div>
          </div>
          <div className="hero-photo">
            <Photo
              src="/images/court-aerial.jpg"
              alt="Sân tennis xanh nhìn từ trên cao — ảnh minh họa thể thao"
              fetchPriority="high"
            />
            <div className="hero-caption">
              <div>
                <small>Cùng nhau ra sân</small>
                <strong>Ra sân. Kết nối. Hết mình.</strong>
              </div>
              <span>
                <ArrowUpRight size={21} />
              </span>
            </div>
          </div>
        </div>
        <form
          className="hero-search"
          onSubmit={(e) => {
            e.preventDefault();
            router.push("/search?" + formQuery(e.currentTarget));
          }}
        >
          <Field
            label="BẠN MUỐN CHƠI Ở ĐÂU?"
            name="q"
            placeholder="Tên cơ sở, khu vực…"
            maxLength={180}
          />
          <Select label="Môn thể thao" name="sportCategoryId">
            <option value="">Tất cả bộ môn</option>
            {categories.data
              ?.filter((x) => x.active)
              .map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}
                </option>
              ))}
          </Select>
          <Field
            label="Ngày chơi"
            type="date"
            name="date"
            defaultValue={today()}
            min={today()}
            required
          />
          <Button type="submit">
            <Search size={18} />
            Tìm sân
          </Button>
        </form>
      </section>
      <div className="container">
        <section>
          <div className="section-heading">
            <div>
              <p className="eyebrow">CHỌN CUỘC CHƠI CỦA BẠN</p>
              <h2>Bộ môn yêu thích</h2>
            </div>
            <p>Mỗi ngày, một lý do để vận động.</p>
          </div>
          <ResourceState
            resource={categories}
            empty={categories.data?.length === 0}
            emptyTitle="Chưa có bộ môn"
            emptyDescription="Danh mục bộ môn sẽ được cập nhật từ hệ thống."
          >
            <div className="sport-grid">
              {categories.data
                ?.filter((x) => x.active)
                .map((x) => (
                  <Link
                    href={`/search?sportCategoryId=${x.id}`}
                    key={x.id}
                    className="sport-card"
                  >
                    <span className="sport-icon">
                      <Activity size={24} />
                    </span>
                    <div>
                      <strong>{x.name}</strong>
                      <small>
                        Khám phá sân{" "}
                        <ArrowUpRight size={10} style={{ display: "inline" }} />
                      </small>
                    </div>
                  </Link>
                ))}
            </div>
          </ResourceState>
        </section>
        <section style={{ marginTop: 54 }}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">SẴN SÀNG CHO HÔM NAY</p>
              <h2>Tìm sân, bắt đầu cuộc chơi</h2>
              <p>Những sân có lịch trống hôm nay, cập nhật từ cơ sở.</p>
            </div>
            <LinkButton href="/search" variant="secondary" arrow>
              Xem tất cả
            </LinkButton>
          </div>
          <ResourceState
            resource={results}
            empty={results.data?.items.length === 0}
            emptyTitle="Hôm nay chưa có lịch trống"
            emptyDescription="Thử tìm một ngày khác để chọn lịch phù hợp với bạn."
          >
            <div className="venue-grid">
              {results.data?.items.slice(0, 3).map((item) => (
                <VenueCard key={item.facility.id} item={item} />
              ))}
            </div>
          </ResourceState>
        </section>
        <section className="feature-band">
          <div>
            <p className="eyebrow">ÍT SẮP XẾP HƠN. NHIỀU CUỘC CHƠI HƠN.</p>
            <h2>
              Từ kế hoạch
              <br />
              đến khoảnh khắc ra sân.
            </h2>
            <p>
              Đặt sân, mời đồng đội và theo dõi chi phí ngay trong một trải
              nghiệm liền mạch.
            </p>
            <LinkButton href="/customer/groups" variant="lime" arrow>
              Đặt sân cùng nhóm
            </LinkButton>
          </div>
          <div className="feature-steps">
            {[
              [
                "01",
                "Chọn lịch phù hợp",
                "Xem lịch trống và giá theo từng khung giờ.",
              ],
              [
                "02",
                "Rủ cả nhóm tham gia",
                "Chia sẻ lời mời và phân bổ chi phí cho từng người.",
              ],
              [
                "03",
                "Sẵn sàng ra sân",
                "Theo dõi thanh toán và vé check-in của bạn.",
              ],
            ].map(([n, t, d]) => (
              <article key={n}>
                <span>{n}</span>
                <div>
                  <h3>{t}</h3>
                  <p>{d}</p>
                </div>
              </article>
            ))}
          </div>
        </section>
        <section
          className="section-heading"
          style={{ marginTop: 52, marginBottom: 0 }}
        >
          <div>
            <p className="eyebrow">Dành cho chủ sân</p>
            <h2>Cơ sở của bạn. Vận hành dễ hơn.</h2>
            <p>
              Quản lý sân, lịch đặt và đội ngũ trên một không gian thống nhất.
            </p>
          </div>
          <LinkButton href="/owner/apply" variant="secondary" arrow>
            Trở thành đối tác
          </LinkButton>
        </section>
      </div>
    </>
  );
}
function SearchLocationFields({ provinceCode, wardCode }: { provinceCode: string; wardCode: string }) {
  const [selectedProvince, setSelectedProvince] = useState(provinceCode);
  const [selectedWard, setSelectedWard] = useState(wardCode);
  const wards = provinceByCode(selectedProvince)?.wards ?? [];
  return (
    <>
      <Select label="Tỉnh / Thành phố" name="provinceCode" value={selectedProvince}
        onChange={(event) => { setSelectedProvince(event.target.value); setSelectedWard(""); }}>
        <option value="">Chọn tỉnh / thành phố</option>
        {provinces.map((province) => <option key={province.code} value={province.code}>{province.name}</option>)}
      </Select>
      <Select label="Xã / Phường" name="wardCode" value={selectedWard} disabled={!selectedProvince}
        onChange={(event) => setSelectedWard(event.target.value)}>
        <option value="">Chọn xã / phường</option>
        {wards.map((ward) => <option key={ward.code} value={ward.code}>{ward.name}</option>)}
      </Select>
    </>
  );
}
export function SearchPage() {
  const params = useSearchParams(),
    router = useRouter();
  const [mode, setMode] = useState("list"),
    [filtersOpen, setFiltersOpen] = useState(false),
    [locationError, setLocationError] = useState("");
  const categories = useResource<Category[]>("/sport-categories");
  const query = params.toString();
  const provinceCode = params.get("provinceCode") ?? provinceByName(params.get("province"))?.code ?? "";
  const wardCode = params.get("wardCode") ?? "";
  const apiParams = discoveryQuery(new URLSearchParams(query));
  if (provinceCode) apiParams.set("provinceCode", provinceCode);
  if (!apiParams.has("date")) apiParams.set("date", today());
  apiParams.set("page", params.get("page") ?? "1");
  apiParams.set("size", "12");
  const results = useResource<FacilitySearchResult>("/bookings/search/facilities?" + apiParams.toString());
  const filteredItems = results.data?.items;
  function changePage(page: number) {
    const next = new URLSearchParams(apiParams); next.set("page", String(page)); router.push("/search?" + next);
  }
  function locate() {
    if (!navigator.geolocation) {
      setLocationError(
        "Trình duyệt chưa hỗ trợ vị trí. Hãy chọn tỉnh hoặc xã/phường.",
      );
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const p = new URLSearchParams(query);
        p.set("latitude", String(position.coords.latitude));
        p.set("longitude", String(position.coords.longitude));
        p.set("sort", "DISTANCE");
        router.push("/search?" + p);
        setLocationError("");
      },
      () =>
        setLocationError(
          "Không lấy được vị trí. Bạn có thể chọn tỉnh hoặc xã/phường.",
        ),
    );
  }
  return (
    <div className="container">
      <PageHead title="Tìm sân thể thao" />
      <div className="search-layout">
        <Panel
          title="Bộ lọc tìm kiếm"
          className={`filter-panel ${filtersOpen ? "filter-open" : ""}`}
          action={<button type="button" className="icon-btn filter-toggle"
            aria-label={filtersOpen ? "Ẩn bộ lọc" : "Hiện bộ lọc"}
            aria-expanded={filtersOpen} aria-controls="search-filter-fields"
            onClick={() => setFiltersOpen(!filtersOpen)}><SlidersHorizontal size={17} /></button>}
        >
          <form
            id="search-filter-fields"
            key={query}
            onSubmit={(e) => {
              e.preventDefault();
              router.push("/search?" + formQuery(e.currentTarget));
            }}
          >
            <Field
              label="Tên cơ sở"
              name="q"
              defaultValue={params.get("q") ?? ""}
              placeholder="Bạn đang tìm sân nào?"
              maxLength={180}
            />
            <Select
              label="Môn thể thao"
              name="sportCategoryId"
              defaultValue={params.get("sportCategoryId") ?? ""}
            >
              <option value="">Tất cả bộ môn</option>
              {categories.data
                ?.filter((x) => x.active)
                .map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.name}
                  </option>
                ))}
            </Select>
            <Field
              name="date"
              label="Ngày chơi"
              type="date"
              min={today()}
              defaultValue={params.get("date") ?? today()}
              required
            />
            <SearchLocationFields provinceCode={provinceCode} wardCode={wardCode} />
            <fieldset className="search-filter-range">
              <legend>Thời gian chơi</legend>
              <div className="search-range-inputs">
                <input aria-label="Từ giờ" name="opensAfter" type="time" defaultValue={params.get("opensAfter") ?? ""} />
                <ArrowRight size={16} aria-hidden="true" />
                <input aria-label="Đến giờ" name="closesBefore" type="time" defaultValue={params.get("closesBefore") ?? ""} />
              </div>
            </fieldset>
            <fieldset className="search-filter-range">
              <legend>Khoảng giá <span className="muted small">(đ/khung giờ)</span></legend>
              <div className="search-range-inputs">
                <input aria-label="Giá từ (đ/khung giờ)" name="minPrice" type="number" min={0} placeholder="Từ" defaultValue={params.get("minPrice") ?? ""} />
                <ArrowRight size={16} aria-hidden="true" />
                <input aria-label="Giá đến (đ/khung giờ)" name="maxPrice" type="number" min={0} placeholder="Đến" defaultValue={params.get("maxPrice") ?? ""} />
              </div>
            </fieldset>
            <Select
              label="Sắp xếp"
              name="sort"
              defaultValue={params.get("sort") ?? "NAME"}
            >
              <option value="NAME">Tên cơ sở</option>
              <option value="PRICE_ASC">Giá thấp đến cao</option>
              <option value="PRICE_DESC">Giá cao đến thấp</option>
              {params.has("latitude") && (
                <option value="DISTANCE">Gần tôi nhất</option>
              )}
            </Select>
            {["latitude", "longitude"].map(
              (k) =>
                params.get(k) && (
                  <input
                    key={k}
                    type="hidden"
                    name={k}
                    value={params.get(k)!}
                  />
                ),
            )}
            {params.has("latitude") && (
              <Field
                label="Bán kính (km)"
                name="radiusKm"
                type="number"
                min={1}
                max={500}
                defaultValue={params.get("radiusKm") ?? ""}
              />
            )}
            <Button>
              <Search size={16} />
              Tìm sân
            </Button>
            <div className="actions">
              <Button type="button" variant="ghost" onClick={locate}>
                <MapPin size={14} />
                Gần tôi
              </Button>
              <Link href="/search" className="small text-link">
                Xóa bộ lọc
              </Link>
            </div>
          </form>
        </Panel>
        <section>
          {locationError && <Notice tone="warning">{locationError}</Notice>}
          <div className="results-toolbar">
            <p>
              <strong>{results.data?.totalFacilities ?? "…"}</strong> cơ sở phù hợp
            </p>
            <Tabs
              value={mode}
              onChange={setMode}
              items={[
                ["list", "Danh sách"],
                ["map", "Bản đồ"],
              ]}
            />
          </div>
          <ResourceState
            resource={results}
            empty={filteredItems?.length === 0}
            emptyTitle="Chưa tìm thấy sân phù hợp"
            emptyDescription="Thử đổi ngày, khung giờ hoặc mở rộng khu vực tìm kiếm."
          >
            {mode === "map" ? (
              filteredItems?.some((x) => x.facility.latitude !== null) ? (
                <CourtMap items={filteredItems ?? []} query={apiParams} />
              ) : (
                <Empty
                  title="Chưa có tọa độ bản đồ"
                  description="Các cơ sở này chưa cập nhật vị trí. Xem địa chỉ trong danh sách."
                />
              )
            ) : (
              <div className="venue-grid">
                {filteredItems?.map((item) => (
                  <VenueCard key={item.facility.id} item={item} query={apiParams} />
                ))}
              </div>
            )}
          </ResourceState>
          {results.data && results.data.totalPages > 1 && (
            <nav className="actions search-pagination" aria-label="Phân trang cơ sở">
              <Button variant="secondary" disabled={results.loading || results.data.page <= 1} onClick={() => changePage(results.data!.page - 1)}>Trang trước</Button>
              <span>Trang {results.data.page} / {results.data.totalPages}</span>
              <Button variant="secondary" disabled={results.loading || results.data.page >= results.data.totalPages} onClick={() => changePage(results.data!.page + 1)}>Trang sau</Button>
            </nav>
          )}
        </section>
      </div>
    </div>
  );
}
export function FacilityPage({ id }: { id: string }) {
  const params = useSearchParams();
  const resource = useResource<Facility>(`/facilities/${id}`),
    images = useResource<Media[]>(`/facilities/${id}/images`);
  const [date, setDate] = useState(params.get("date") ?? today());
  const query = discoveryQuery(new URLSearchParams(params.toString()), date);
  query.set("facilityId", id); query.set("size", "1");
  const matches = useResource<FacilitySearchResult>("/bookings/search/facilities?" + query);
  const matchingCourts = matches.data?.items[0]?.courts;
  const f = resource.data;
  return (
    <div className="container">
      <ResourceState resource={resource}>
        {f && (
          <>
            <PageHead
              eyebrow="Thông tin cơ sở"
              title={f.name}
              description={[f.addressLine, f.ward, f.province].filter(Boolean).join(", ")}
              action={<Badge state={f.status} />}
            />
            {images.data?.length ? (
              <div className="gallery">
                <Photo src={images.data[0].url} alt={f.name} />
                <div className="gallery-side">
                  {images.data.slice(1, 3).map((m) => (
                    <Photo key={m.id} src={m.url} alt="Không gian cơ sở" />
                  ))}
                </div>
              </div>
            ) : (
              <div className="gallery-empty" style={{ marginBottom: 32 }}>
                <Building2 size={40} />
                <p>Cơ sở chưa cập nhật hình ảnh</p>
              </div>
            )}
            <div className="detail-layout">
              <div className="stack">
                <Panel title="Không gian cho cuộc chơi của bạn">
                  <p className="muted small">
                    {f.description || "Cơ sở chưa cung cấp mô tả chi tiết."}
                  </p>
                  <div className="amenities">
                    {f.amenities.map((a) => (
                      <span key={a}>
                        <Check size={15} />
                        {a}
                      </span>
                    ))}
                  </div>
                </Panel>
                <section>
                  <h2 style={{ marginBottom: 20 }}>Chọn sân của bạn</h2>
                  <ResourceState
                    resource={matches}
                    empty={matches.data?.items.length === 0}
                    emptyTitle="Chưa có sân phù hợp"
                    emptyDescription="Thử đổi ngày hoặc mở rộng khung giờ, bộ môn và khoảng giá."
                  >
                    <div className="court-list">
                      {matchingCourts?.map((c) => (
                        <div className="court-row" key={c.courtId}>
                          <div className="court-identity">
                            <Activity />
                            <div>
                              <h3>{c.courtName}</h3>
                              <p>{c.sportName} · {c.availableSlots} khung giờ phù hợp · Từ {money(c.fromPrice, c.currency)}</p>
                            </div>
                          </div>
                          <LinkButton href={courtSearchHref(id, c, query)} variant="secondary" arrow>
                            Chọn sân và giờ chơi
                          </LinkButton>
                        </div>
                      ))}
                    </div>
                  </ResourceState>
                </section>
              </div>
              <Panel className="booking-aside" title="Hẹn một cuộc chơi">
                <Field
                  label="Ngày bạn muốn ra sân"
                  type="date"
                  value={date}
                  min={today()}
                  onChange={(e) => setDate(e.target.value)}
                />
                <div style={{ marginTop: 20 }}>
                  <p className="small muted">
                    <Clock3
                      size={14}
                      style={{ display: "inline", marginRight: 8 }}
                    />
                    Giờ hoạt động và giá được hiển thị theo từng sân trong lịch
                    trống.
                  </p>
                  <p className="small muted" style={{ marginTop: 12 }}>
                    <MapPin
                      size={14}
                      style={{ display: "inline", marginRight: 8 }}
                    />
                    {f.addressLine}
                  </p>
                  {f.phone && (
                    <a
                      className="text-link small"
                      style={{ display: "block", marginTop: 18 }}
                      href={`tel:${f.phone}`}
                    >
                      Liên hệ cơ sở: {f.phone}
                    </a>
                  )}
                  {f.latitude !== null && f.longitude !== null && (
                    <a
                      className="btn btn-secondary"
                      style={{ marginTop: 18, width: "100%" }}
                      href={`https://www.google.com/maps/dir/?api=1&destination=${f.latitude},${f.longitude}`}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Chỉ đường <ArrowUpRight size={15} />
                    </a>
                  )}
                </div>
              </Panel>
            </div>
          </>
        )}
      </ResourceState>
    </div>
  );
}
export function AvailabilityPage({
  facilityId,
  courtId,
  counter = false,
}: {
  facilityId: string;
  courtId: string;
  counter?: boolean;
}) {
  const params = useSearchParams(),
    router = useRouter(),
    { user } = useAuth();
  const [date, setDate] = useState(params.get("date") ?? today()),
    [selected, setSelected] = useState<string[]>([]);
  const context = useResource<{ facility: Facility; court: Court }>(
    `/facilities/courts/${courtId}/context`,
  );
  const resource = useResource<Preview>(
    `/bookings/availability?courtId=${courtId}&date=${date}`,
  );
  const initial =
    resource.data?.slots
      .filter(
        (s) =>
          params.get("start") &&
          s.startsAt >= params.get("start")! &&
          s.endsAt <= params.get("end")!,
      )
      .map((s) => s.startsAt) ?? [];
  const selection = selected.length ? selected : initial;
  const interval = selectedInterval(resource.data?.slots ?? [], selection);
  const quote = useResource<Quote>(
    interval ? "/schedules/public/quote" : null,
    interval ? JSON.stringify({ courtId, ...interval }) : undefined,
  );
  const action = useAction(),
    idempotency = useIdempotency();
  const now = useCurrentTime();
  const zone = resource.data?.timezone ?? "Asia/Ho_Chi_Minh";
  async function hold() {
    if (!quote.data) return;
    const body = {
      courtId,
      startsAt: quote.data.startsAt,
      endsAt: quote.data.endsAt,
      expectedAmount: quote.data.amount,
    };
    await action.run(
      () =>
        api<Hold>(`/bookings/holds${counter ? "?counter=true" : ""}`, {
          method: "POST",
          headers: { "Idempotency-Key": idempotency(JSON.stringify(body)) },
          body: JSON.stringify(body),
        }),
      (h) =>
        router.push(
          `/customer/booking/holds/${h.id}${counter ? "?counter=true" : ""}`,
        ),
    );
  }
  function toggle(start: string) {
    const current = selection.includes(start)
      ? selection.filter((x) => x !== start)
      : [...selection, start];
    setSelected(current);
    if (params.has("start"))
      router.replace(
        `${counter ? "/staff/counter-booking/" + courtId : `/facilities/${facilityId}/courts/${courtId}`}?${discoveryQuery(new URLSearchParams(params.toString()), date)}`,
      );
  }
  return (
    <div className="container">
      <PageHead
        eyebrow="Chọn sân và giờ chơi"
        title={context.data?.court.name ?? "Chọn lịch chơi"}
        description={
          context.data
            ? context.data.facility.name +
              " · " +
              context.data.facility.addressLine
            : "Xem lịch trống và giá theo từng khung giờ."
        }
        action={
          <Button
            variant="secondary"
            onClick={() => {
              resource.reload();
              quote.reload();
            }}
          >
            Làm mới lịch
          </Button>
        }
      />
      {context.error && <Notice tone="error">{context.error}</Notice>}
      <Stepper current={0} />
      <div className="detail-layout">
        <Panel
          title="Chọn thời gian ra sân"
          description="Bạn có thể chọn nhiều khung giờ trống liên tiếp."
        >
          <Field
            label="Ngày chơi"
            type="date"
            value={date}
            min={today()}
            onChange={(e) => {
              setDate(e.target.value);
              setSelected([]);
              if (params.has("start"))
                router.replace(`?${discoveryQuery(new URLSearchParams(params.toString()), e.target.value)}`);
            }}
          />
          <div className="date-strip">
            {Array.from({ length: 7 }, (_, i) => {
              const d = new Date(today() + "T12:00:00+07:00");
              d.setUTCDate(d.getUTCDate() + i);
              const key = d.toISOString().slice(0, 10);
              return (
                <button
                  type="button"
                  key={key}
                  className={date === key ? "active" : ""}
                  onClick={() => {
                    setDate(key);
                    setSelected([]);
                    if (params.has("start")) router.replace(`?${discoveryQuery(new URLSearchParams(params.toString()), key)}`);
                  }}
                >
                  <span>
                    {
                      [
                        "CN",
                        "Thứ 2",
                        "Thứ 3",
                        "Thứ 4",
                        "Thứ 5",
                        "Thứ 6",
                        "Thứ 7",
                      ][d.getUTCDay()]
                    }
                  </span>
                  <strong>{d.getUTCDate()}</strong>
                </button>
              );
            })}
          </div>
          <div className="slot-legend">
            <span>
              <i />
              Còn trống
            </span>
            <span>
              <i className="busy" />
              Đã đặt
            </span>
            <span>
              <i className="held" />
              Đang giữ
            </span>
            <span>
              <i className="maintenance" />
              Bảo trì / đóng cửa
            </span>
          </div>
          <ResourceState
            resource={resource}
            empty={resource.data?.slots.length === 0}
            emptyTitle="Ngày này chưa có lịch mở sân"
            emptyDescription="Chọn ngày khác hoặc liên hệ cơ sở để biết thêm thông tin."
          >
            <div className="slot-grid">
              {resource.data?.slots.map((s) => (
                <button
                  type="button"
                  className={`slot ${selection.includes(s.startsAt) ? "selected" : ""}`}
                  data-state={s.state}
                  data-reason={s.reason}
                  key={s.startsAt}
                  disabled={
                    s.state !== "AVAILABLE" ||
                    s.amount === null ||
                    (now !== null && new Date(s.startsAt).getTime() <= now)
                  }
                  aria-pressed={selection.includes(s.startsAt)}
                  aria-label={`${time(s.startsAt, zone)} đến ${time(s.endsAt, zone)}, ${label(s.reason ?? s.state)}, ${s.amount === null ? "chưa có giá" : money(s.amount, s.currency)}`}
                  onClick={() => toggle(s.startsAt)}
                  title={
                    s.reason ? label(s.reason) : (s.ruleLabel ?? undefined)
                  }
                >
                  <strong>
                    {time(s.startsAt, zone)} – {time(s.endsAt, zone)}
                  </strong>
                  <small>
                    {s.state === "AVAILABLE" && s.amount !== null
                      ? money(s.amount, s.currency)
                      : label(s.reason ?? s.state)}
                  </small>
                </button>
              ))}
            </div>
          </ResourceState>
        </Panel>
        <Panel className="booking-aside" title="Lịch chơi của bạn">
          <p className="small muted">{context.data?.facility.name}</p>
          {selection.length && !interval ? (
            <Notice tone="warning">
              Chọn các khung giờ trống liên tiếp để tạo một lượt đặt.
            </Notice>
          ) : null}
          {quote.loading ? (
            <p className="small muted">Đang kiểm tra giá…</p>
          ) : quote.data ? (
            <>
              <p style={{ fontWeight: 600, marginTop: 20 }}>
                {time(quote.data.startsAt, zone)} –{" "}
                {time(quote.data.endsAt, zone)}
              </p>
              <p className="muted small">
                {date} · {selection.length} khung giờ
              </p>
              <div style={{ margin: "20px 0" }}>
                <QuoteBreakdown quote={quote.data} />
              </div>
              <dl className="detail-list price-breakdown">
                <div>
                  <dt>Tổng chi phí</dt>
                  <dd style={{ fontSize: 22, color: "var(--brand)" }}>
                    {money(quote.data.amount, quote.data.currency)}
                  </dd>
                </div>
              </dl>
            </>
          ) : (
            <Empty
              title="Chọn một khung giờ"
              description="Chi tiết chi phí sẽ hiển thị sau khi chọn lịch."
            />
          )}
          {quote.error && <Notice tone="error">{quote.error}</Notice>}
          <Feedback action={action} />
          {user ? (
            <Button
              busy={action.busy}
              disabled={!quote.data || quote.refreshing}
              onClick={hold}
            >
              Tiếp tục đặt sân <ArrowRight size={17} />
            </Button>
          ) : (
            <LinkButton
              href={`/login?next=${encodeURIComponent(`/facilities/${facilityId}/courts/${courtId}?date=${date}${interval ? "&start=" + interval.startsAt + "&end=" + interval.endsAt : ""}`)}`}
            >
              Đăng nhập để đặt sân
            </LinkButton>
          )}
          <p className="muted small" style={{ marginTop: 14 }}>
            Lịch trống chỉ được giữ khi bạn tiếp tục và hệ thống xác nhận.
          </p>
        </Panel>
      </div>
    </div>
  );
}
