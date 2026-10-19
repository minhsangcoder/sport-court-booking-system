import { LinkButton } from "@/rebuilt/components/ui";
export default function NotFound() {
  return (
    <div className="container narrow empty-state">
      <p className="eyebrow">404 · OUT OF COURT</p>
      <h1>Trang này không còn trong cuộc chơi.</h1>
      <p>
        Đường dẫn không tồn tại. Khám phá sân hoặc trở về trang chủ để tiếp tục.
      </p>
      <div className="actions">
        <LinkButton href="/search">Khám phá sân</LinkButton>
        <LinkButton href="/" variant="secondary">
          Về trang chủ
        </LinkButton>
      </div>
    </div>
  );
}
