/**
 * PCM 采集处理器。
 *
 * 百炼实时识别只收 16kHz / 16bit / 单声道 PCM，所以这里把 Float32 的麦克风采样
 * 攒成 100ms 一批（1600 个采样）转成 Int16 再发出去。
 *
 * 放在 public/ 而不是 src/：AudioWorklet 的模块必须是一个可以原样加载的独立脚本，
 * 走 Vite 的模块图反而会被包成 ESM 而加载失败。
 */
class PcmProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const config = (options && options.processorOptions) || {}
    this.frameSamples = config.frameSamples || 1600
    this.buffer = new Float32Array(this.frameSamples)
    this.offset = 0
    this.port.onmessage = event => {
      if (event.data === 'flush') {
        this.flush()
      }
    }
  }

  flush() {
    if (this.offset === 0) {
      return
    }
    const output = new Int16Array(this.offset)
    for (let i = 0; i < this.offset; i += 1) {
      const sample = Math.max(-1, Math.min(1, this.buffer[i]))
      output[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff
    }
    this.port.postMessage(output, [output.buffer])
    this.offset = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) {
      return true
    }
    for (let i = 0; i < channel.length; i += 1) {
      this.buffer[this.offset] = channel[i]
      this.offset += 1
      if (this.offset === this.frameSamples) {
        this.flush()
      }
    }
    return true
  }
}

registerProcessor('pcm-processor', PcmProcessor)
