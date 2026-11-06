import type { HighlightRect, Level, PreviewPage } from '@/types/api'

export interface PageMark {
  pointId: string
  level: Level
  page: number
  rect: HighlightRect
  /** 同一批注在一页上可能由多段矩形组成，只用第一段参与连线测量。 */
  first: boolean
}

interface Props {
  pages: PreviewPage[]
  images: string[]
  marks: PageMark[]
  hoveredId: string | null
  onHover: (id: string | null) => void
  registerMark: (pointId: string, el: HTMLElement | null) => void
}

/**
 * 原版简历：直接把 PDF 每页渲染图铺出来，批注以高亮框叠在原文上。
 *
 * <p>这里不重新排版任何文字——原文件的字体、分栏、右对齐日期都保留在图片里，
 * 所以看起来和用户上传的简历一模一样。矩形坐标是 PDF 点，按百分比换算，
 * 窗口缩放或换分辨率都不会错位。</p>
 */
export function ResumeOriginal({ pages, images, marks, hoveredId, onHover, registerMark }: Props) {
  return (
    <article className="paper-original">
      {pages.map((page, index) => (
        <div
          key={page.index}
          className="resume-page"
          style={{ aspectRatio: `${page.width} / ${page.height}` }}
        >
          {images[index] ? (
            <img
              className="resume-page-image"
              src={images[index]}
              alt={`简历第 ${page.index} 页`}
              draggable={false}
            />
          ) : (
            <div className="resume-page-loading">正在渲染第 {page.index} 页…</div>
          )}
          <div className="resume-page-overlay">
            {marks
              .filter(mark => mark.page === page.index)
              .map(mark => (
                <div
                  key={`${mark.pointId}-${mark.rect.x}-${mark.rect.y}-${mark.rect.width}`}
                  ref={mark.first ? element => registerMark(mark.pointId, element) : undefined}
                  className={`resume-hit${hoveredId === mark.pointId ? ' is-hovered' : ''}`}
                  data-level={mark.level}
                  style={{
                    left: `${(mark.rect.x / page.width) * 100}%`,
                    top: `${(mark.rect.y / page.height) * 100}%`,
                    width: `${(mark.rect.width / page.width) * 100}%`,
                    height: `${(mark.rect.height / page.height) * 100}%`
                  }}
                  onMouseEnter={() => onHover(mark.pointId)}
                  onMouseLeave={() => onHover(null)}
                />
              ))}
          </div>
        </div>
      ))}
    </article>
  )
}
