// make-spatial.swift LEFT.png RIGHT.png OUT.mov SECONDS [rect|hequ|equi] — an MV-HEVC stereo clip as Apple's
// own encoder writes it (VideoToolbox, the way iPhone spatial video and Apple's projected media are made):
// the left eye in the base layer, the right in the second, with Apple's stereo/projection metadata (vexu).
//   rect: a spatial video (rectilinear, 64° across, 64 mm baseline) — iPhone / PICO 4 Ultra style
//   hequ: half-equirectangular 180° (Apple Projected Media Profile, VR180)
//   equi: equirectangular 360° (APMP)
// (Apple's fisheyes are ParametricImmersive, which needs the lens's calibration — not made here.)
import AVFoundation
import CoreImage
import VideoToolbox

let args = CommandLine.arguments
guard args.count >= 5 else { print("usage: make-spatial LEFT RIGHT OUT SECONDS [rect|hequ|equi]"); exit(2) }
let kind = args.count > 5 ? args[5] : "rect"
let ctx = CIContext()
func load(_ path: String) -> CIImage { CIImage(contentsOf: URL(fileURLWithPath: path))! }
let left = load(args[1]), right = load(args[2])
let w = Int(left.extent.width), h = Int(left.extent.height)
let out = URL(fileURLWithPath: args[3])
try? FileManager.default.removeItem(at: out)
let seconds = Double(args[4])!

var compression: [String: Any] = [
    kVTCompressionPropertyKey_MVHEVCVideoLayerIDs as String: [0, 1],
    kVTCompressionPropertyKey_MVHEVCViewIDs as String: [0, 1],
    kVTCompressionPropertyKey_MVHEVCLeftAndRightViewIDs as String: [0, 1],
    kVTCompressionPropertyKey_HasLeftStereoEyeView as String: true,
    kVTCompressionPropertyKey_HasRightStereoEyeView as String: true,
    kVTCompressionPropertyKey_AverageBitRate as String: 4_000_000,
]
switch kind {
case "hequ":
    compression[kVTCompressionPropertyKey_ProjectionKind as String] = kCMFormatDescriptionProjectionKind_HalfEquirectangular
case "equi":
    compression[kVTCompressionPropertyKey_ProjectionKind as String] = kCMFormatDescriptionProjectionKind_Equirectangular
default:
    compression[kVTCompressionPropertyKey_ProjectionKind as String] = kCMFormatDescriptionProjectionKind_Rectilinear
    compression[kVTCompressionPropertyKey_HorizontalFieldOfView as String] = 64_000   // thousandths of a degree
    compression[kVTCompressionPropertyKey_StereoCameraBaseline as String] = 64_000    // micrometres
    compression[kVTCompressionPropertyKey_HorizontalDisparityAdjustment as String] = 200
}
let settings: [String: Any] = [
    AVVideoCodecKey: AVVideoCodecType.hevc,
    AVVideoWidthKey: w,
    AVVideoHeightKey: h,
    AVVideoCompressionPropertiesKey: compression,
]
let writer = try AVAssetWriter(outputURL: out, fileType: .mov)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputTaggedPixelBufferGroupAdaptor(
    assetWriterInput: input,
    sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: w,
        kCVPixelBufferHeightKey as String: h,
    ])
writer.add(input)
guard writer.startWriting() else { print("cannot write: \(String(describing: writer.error))"); exit(1) }
writer.startSession(atSourceTime: .zero)

func buffer(_ img: CIImage) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
    ctx.render(img, to: pb!)
    return pb!
}
let leftPB = buffer(left), rightPB = buffer(right)
let fps: Int32 = 30
let frames = Int(seconds * Double(fps))
for i in 0..<frames {
    while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
    let tagged = [
        CMTaggedBuffer(tags: [.stereoView(.leftEye), .videoLayerID(0)], pixelBuffer: leftPB),
        CMTaggedBuffer(tags: [.stereoView(.rightEye), .videoLayerID(1)], pixelBuffer: rightPB),
    ]
    if !adaptor.appendTaggedBuffers(tagged, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)) {
        print("append failed at \(i): \(String(describing: writer.error))"); exit(1)
    }
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
print(writer.status == .completed ? "wrote \(out.path) \(w)x\(h) \(kind)" : "failed: \(String(describing: writer.error))")
