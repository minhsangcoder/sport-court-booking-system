import { type Quote, money, time } from "@/rebuilt/lib/domain";

export function QuoteBreakdown({ quote }: { quote: Quote }) {
  return (
    <dl className="detail-list price-breakdown" aria-label="Chi phí từng khung giờ">
      {quote.segments.map((segment) => (
        <div key={segment.startsAt}>
          <dt>
            {time(segment.startsAt, quote.timezone)} –{" "}
            {time(segment.endsAt, quote.timezone)}
          </dt>
          <dd>
            {segment.amount === null
              ? "Chưa xác định"
              : money(segment.amount, segment.currency)}
          </dd>
        </div>
      ))}
    </dl>
  );
}
