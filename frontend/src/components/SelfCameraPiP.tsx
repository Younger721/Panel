import { useEffect, useRef, useState } from 'react'
import { CameraOff } from 'lucide-react'

/**
 * 自己的摄像头小窗。
 *
 * 只做本地预览：<b>不录制、不上传、不做任何检测</b>。
 * 所以文案是「保持正对镜头」，不写「作弊监控中」——那是另一种产品。
 */
export default function SelfCameraPiP({ enabled }: { enabled: boolean }) {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!enabled) {
      return
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setFailed(true)
      return
    }
    let cancelled = false
    navigator.mediaDevices
      .getUserMedia({ video: { width: 640, height: 480, facingMode: 'user' }, audio: false })
      .then(stream => {
        if (cancelled) {
          stream.getTracks().forEach(track => track.stop())
          return
        }
        streamRef.current = stream
        if (videoRef.current) {
          videoRef.current.srcObject = stream
          void videoRef.current.play().catch(() => undefined)
        }
        setFailed(false)
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true)
        }
      })

    return () => {
      cancelled = true
      // 不 stop 会导致指示灯常亮、摄像头一直被占用
      streamRef.current?.getTracks().forEach(track => track.stop())
      streamRef.current = null
      if (videoRef.current) {
        videoRef.current.srcObject = null
      }
    }
  }, [enabled])

  if (!enabled) {
    return null
  }

  return (
    <div className="relative aspect-[4/3] w-full overflow-hidden rounded-xl border border-white bg-slate-900 shadow-[0_18px_40px_-24px_rgba(15,23,42,0.55)]">
      {failed ? (
        <div className="flex h-full w-full flex-col items-center justify-center gap-1 text-slate-400">
          <CameraOff className="h-5 w-5" />
          <span className="text-[10px]">摄像头不可用</span>
        </div>
      ) : (
        <video ref={videoRef} muted playsInline className="h-full w-full scale-x-[-1] object-cover" />
      )}
    </div>
  )
}
