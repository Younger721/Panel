import { useState, type CSSProperties } from 'react'

export type InterviewerPhase = 'PREPARING' | 'THINKING' | 'SPEAKING' | 'LISTENING' | 'FINISHED'

const BASE_SRC = '/interviewer/base.webp'

/**
 * 面试官：一张静态人像 + 一层很轻的呼吸。
 *
 * 口型不跟着声音动：素材是 AI 生成的单帧，另一张口型帧和它对不齐，
 * 换嘴或换整帧都会在眼睛、头发上留下重影，动起来比不动更出戏。
 * 所以这里只保留 0.6% 的呼吸缩放——目的只是让整块画面不像一张贴图。
 *
 * 素材是 idle.webp（闭嘴、微笑、不露齿），清完抠图白边后存成 base.webp，
 * 处理见 scripts/build-interviewer-sprites.py。
 */

/**
 * 落位交给父级的 flex 列，不再写死 `top-[35%]`。
 *
 * 上一版的问题是：题面高度由「这一题有多少字」决定，人像位置却是常数，
 * 于是长题（4 行往上）直接压到发顶。手算一个更保守的 35% 只会在别的字数上重犯，
 * 因为真实换行还受字体加载、中英混排影响。
 *
 * 现在的结构是：
 *   [ 题面  auto ]      ← 越长越高
 *   [ 人像  flex-1 ]    ← 只剩这一段
 * 人像顶边 = 题面底边 + 28px（`top-7`），这条间隙由布局保证，不靠测量。
 * 人像高度也因此随题面自动收放，脸会小一点，但绝不会被字盖住。
 */
export const LIFTED_BOX = 'top-7 bottom-20 [@media(max-height:800px)]:bottom-16'

/**
 * 完成态：作答条收起，人像落回底部并放大；压到画面外的下沿靠 overflow 裁掉。
 */
export const FINISHED_BOX = 'top-7 -bottom-[10%]'

/**
 * 左右各留 3.5% 的淡出。
 * 头发和脸都不碰图的左右边界（肩膀从 79% 高度开始才碰到边界），
 * 所以这层羽化只软化那圈被方图裁断的外套侧边，不会动到人物轮廓。
 */
const EDGE_FEATHER: CSSProperties = {
  maskImage:
    'linear-gradient(to right, rgba(0, 0, 0, 0) 0%, #000 3.5%, #000 96.5%, rgba(0, 0, 0, 0) 100%)',
  WebkitMaskImage:
    'linear-gradient(to right, rgba(0, 0, 0, 0) 0%, #000 3.5%, #000 96.5%, rgba(0, 0, 0, 0) 100%)'
}

/** 阴影收在人物脚下，别往左甩一大片：之前那层偏移阴影才是「贴纸感」的主要来源。 */
const DROP_SHADOW = {
  filter:
    'drop-shadow(0 18px 26px rgba(46, 44, 38, 0.16)) drop-shadow(0 2px 6px rgba(46, 44, 38, 0.10))'
}

interface Props {
  /** 作答条在场时人像下沿探到作答条后面；面试结束后作答条收起，人像落到底 */
  lifted: boolean
}

export default function DigitalInterviewer({ lifted }: Props) {
  const [failed, setFailed] = useState(false)

  if (failed) {
    return null
  }

  /* 人像高度 = 「题面以下、作答条以上」剩下的全部，不给最小高度：
     矮屏（768 高）上撑到 380px 会把下巴推进作答条里，而脸是这张图唯一不能裁的部分。
     题面那头有高度上限兜着（InterviewPage 的 QUESTION_BOX），所以这里不会缩到 0。 */
  return (
    /* min-h-0 + flex-1：这块就是「题面以下、作答条以上」的全部剩余空间。
       overflow-hidden 在页面容器上，人像的方图下沿可以探出这块区域、被裁掉。 */
    <div className="pointer-events-none relative z-10 min-h-0 flex-1" style={DROP_SHADOW}>
      <div className={`absolute inset-x-0 ${lifted ? LIFTED_BOX : FINISHED_BOX}`}>
        {/* 方图（aspect-square）的宽高都由高度推出来，水平居中；内层单独负责呼吸动效，
            否则 animation 的 transform 会覆盖掉居中位移。 */}
        <div className="interviewer-breath mx-auto h-full aspect-square" style={EDGE_FEATHER}>
          <img
            src={BASE_SRC}
            alt="面试官"
            onError={() => setFailed(true)}
            className="h-full w-full object-contain object-top"
          />
        </div>
      </div>
    </div>
  )
}
