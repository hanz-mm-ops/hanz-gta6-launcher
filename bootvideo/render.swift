// Renders the TV's startup video (bootvideo.mp4) and matching static logo (logo.jpg).
// Build: swiftc -O render.swift -o render    Run: ./render <out-dir>
import AVFoundation
import AppKit
import CoreGraphics

let W = 1280, H = 720, FPS: Int32 = 30
let seconds = 3.0
let outDir = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")

func ease(_ t: Double) -> Double { let c = min(max(t, 0), 1); return 1 - pow(1 - c, 3) }
func clamp(_ t: Double) -> Double { min(max(t, 0), 1) }

/// The TV glyph: a rounded screen with a short stand, centred at (cx, cy), `w` wide.
func glyphPath(cx: CGFloat, cy: CGFloat, w: CGFloat) -> CGPath {
    let h = w * 0.62, r = w * 0.085
    let p = CGMutablePath()
    p.addRoundedRect(in: CGRect(x: cx - w / 2, y: cy - h / 2 + w * 0.06, width: w, height: h),
                     cornerWidth: r, cornerHeight: r)
    let sw = w * 0.38, sh = w * 0.04
    p.addRoundedRect(in: CGRect(x: cx - sw / 2, y: cy - h / 2 - w * 0.05, width: sw, height: sh),
                     cornerWidth: sh / 2, cornerHeight: sh / 2)
    return p
}

/// Draw one frame at time t (seconds) into a context of size w×h.
func draw(_ ctx: CGContext, t: Double, w: Int, h: Int) {
    ctx.setFillColor(CGColor(gray: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))

    // Visible from the first frame (matches logo.jpg), gentle settle, then fade out.
    let settle = ease(t / 0.9)
    let out = ease((t - 2.2) / 0.8)
    let alpha = 1 - out
    let scale = 0.97 + 0.03 * settle + 0.05 * out
    let gw = CGFloat(w) * 0.17 * CGFloat(scale)
    let path = glyphPath(cx: CGFloat(w) / 2, cy: CGFloat(h) / 2, w: gw)

    // Soft glow, strongest as the glyph settles.
    ctx.saveGState()
    ctx.setShadow(offset: .zero, blur: CGFloat(w) * 0.03,
                  color: CGColor(gray: 1, alpha: 0.45 * alpha * (0.6 + 0.4 * settle)))
    ctx.setFillColor(CGColor(gray: 1, alpha: alpha))
    ctx.addPath(path)
    ctx.fillPath()
    ctx.restoreGState()

    // A diagonal sheen sweeping across the glyph between 0.9 s and 2.0 s.
    let sweep = clamp((t - 0.9) / 1.1)
    if sweep > 0 && sweep < 1 {
        ctx.saveGState()
        ctx.addPath(path)
        ctx.clip()
        let bandX = CGFloat(w) / 2 - gw + CGFloat(ease(sweep)) * gw * 2
        let colors = [CGColor(red: 0.55, green: 0.75, blue: 1, alpha: 0),
                      CGColor(red: 0.55, green: 0.75, blue: 1, alpha: 0.85 * alpha),
                      CGColor(red: 0.55, green: 0.75, blue: 1, alpha: 0)] as CFArray
        let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 0.5, 1])!
        ctx.drawLinearGradient(grad, start: CGPoint(x: bandX - gw * 0.25, y: CGFloat(h) / 2 - gw * 0.3),
                               end: CGPoint(x: bandX + gw * 0.25, y: CGFloat(h) / 2 + gw * 0.3), options: [])
        ctx.restoreGState()
    }
}

func makeContext(w: Int, h: Int, data: UnsafeMutableRawPointer?, bytesPerRow: Int) -> CGContext {
    CGContext(data: data, width: w, height: h, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
}

// ---- logo.jpg: the first frame at 1920×1080.
do {
    let ctx = makeContext(w: 1920, h: 1080, data: nil, bytesPerRow: 0)
    draw(ctx, t: 0, w: 1920, h: 1080)
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try! rep.representation(using: .jpeg, properties: [.compressionFactor: 0.92])!
        .write(to: outDir.appendingPathComponent("logo.jpg"))
}

// ---- bootvideo.mp4: H.264 video plus a silent AAC track, like the original.
let url = outDir.appendingPathComponent("bootvideo.mp4")
try? FileManager.default.removeItem(at: url)
let writer = try! AVAssetWriter(outputURL: url, fileType: .mp4)
let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoProfileLevelKey: AVVideoProfileLevelH264MainAutoLevel,
                                      AVVideoAverageBitRateKey: 2_000_000],
])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H,
])
let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
    AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 64000,
])
writer.add(video)
writer.add(audio)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

// Silence, described once.
var asbd = AudioStreamBasicDescription(mSampleRate: 44100, mFormatID: kAudioFormatLinearPCM,
    mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
    mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 2, mBitsPerChannel: 16, mReserved: 0)
var fmt: CMAudioFormatDescription?
CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0,
                               magicCookie: nil, extensions: nil, formatDescriptionOut: &fmt)

func silence(at sample: Int, count: Int) -> CMSampleBuffer {
    let bytes = count * 4
    var block: CMBlockBuffer?
    CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: bytes, blockAllocator: nil,
                                       customBlockSource: nil, offsetToData: 0, dataLength: bytes, flags: 0, blockBufferOut: &block)
    CMBlockBufferFillDataBytes(with: 0, blockBuffer: block!, offsetIntoDestination: 0, dataLength: bytes)
    var out: CMSampleBuffer?
    CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: block!, formatDescription: fmt!,
        sampleCount: count, presentationTimeStamp: CMTime(value: CMTimeValue(sample), timescale: 44100),
        packetDescriptions: nil, sampleBufferOut: &out)
    return out!
}

// Interleave picture and sound: the writer waits for both to keep pace with each other.
let frames = Int(seconds * Double(FPS))
let samplesPerFrame = 44100 / Int(FPS)
var audioDone = 0
for i in 0..<frames {
    while !video.isReadyForMoreMediaData { usleep(500) }
    var pb: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
    CVPixelBufferLockBaseAddress(pb!, [])
    let ctx = makeContext(w: W, h: H, data: CVPixelBufferGetBaseAddress(pb!), bytesPerRow: CVPixelBufferGetBytesPerRow(pb!))
    draw(ctx, t: Double(i) / Double(FPS), w: W, h: H)
    CVPixelBufferUnlockBaseAddress(pb!, [])
    adaptor.append(pb!, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: FPS))

    while !audio.isReadyForMoreMediaData { usleep(500) }
    audio.append(silence(at: audioDone, count: samplesPerFrame))
    audioDone += samplesPerFrame
}
video.markAsFinished()
audio.markAsFinished()

let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
print(writer.status == .completed ? "wrote \(url.path)" : "failed: \(String(describing: writer.error))")
