"use client";
import { Button, LinkButton, PageHead, Panel } from "@/rebuilt/components/ui";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div className="container narrow">
      <PageHead
        title="Không thể hiển thị trang này"
        description="Đã xảy ra lỗi khi chuẩn bị nội dung. Thử tải lại để tiếp tục."
      />
      <Panel>
        <div className="actions">
          <Button onClick={reset}>Thử lại</Button>
          <LinkButton href="/" variant="secondary">
            Về trang chủ
          </LinkButton>
        </div>
      </Panel>
    </div>
  );
}
