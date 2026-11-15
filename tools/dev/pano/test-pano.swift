// test-pano.swift <out.png> <width> <height> [eye label]
//
// A full 360×180 equirectangular test picture for the immersive player: the hue runs with the yaw,
// a grid line every 15°, every 30° of yaw named on the horizon (0° = FRONT, 90° = RIGHT, 180° = BACK,
// 270° = LEFT), UP and DOWN near the poles, and an eye label ("L"/"R") so a stereo pair can be told
// apart. Converted by FFmpeg's v360 into the other projections (make-pano-tests.sh).
import AppKit

let args = CommandLine.arguments
let out = args[1]
let w = Int(args[2])!
let h = Int(args[3])!
let eye = args.count > 4 ? args[4] : ""

let space = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
// CoreGraphics' origin is bottom-left; work in top-left image coordinates.
ctx.translateBy(x: 0, y: CGFloat(h))
ctx.scaleBy(x: 1, y: -1)

/// Image x for a yaw in degrees (0 = front, at the centre; positive to the right).
func xOf(_ yaw: Double) -> CGFloat { CGFloat((yaw + 180) / 360) * CGFloat(w) }
/// Image y for a pitch in degrees (positive up).
func yOf(_ pitch: Double) -> CGFloat { CGFloat((90 - pitch) / 180) * CGFloat(h) }

// Columns coloured by yaw, darker towards the poles.
let bands = 72
for i in 0..<bands {
    let hue = CGFloat(i) / CGFloat(bands)
    for j in 0..<36 {
        let pitch = 90 - (Double(j) + 0.5) * 5
        let shade = 0.55 + 0.35 * cos(pitch * .pi / 180)
        let c = NSColor(hue: hue, saturation: 0.45, brightness: CGFloat(shade), alpha: 1)
        ctx.setFillColor(c.cgColor)
        let x0 = CGFloat(i) * CGFloat(w) / CGFloat(bands)
        ctx.fill(CGRect(x: x0, y: CGFloat(j) * CGFloat(h) / 36, width: CGFloat(w) / CGFloat(bands) + 1, height: CGFloat(h) / 36 + 1))
    }
}

// Grid: every 15°, the horizon and the 0° meridian heavier.
ctx.setStrokeColor(NSColor.black.withAlphaComponent(0.55).cgColor)
for k in 0...24 {
    let x = xOf(Double(k) * 15 - 180)
    ctx.setLineWidth(k % 6 == 0 ? 4 : 1.5)
    ctx.move(to: CGPoint(x: x, y: 0)); ctx.addLine(to: CGPoint(x: x, y: CGFloat(h))); ctx.strokePath()
}
for k in 0...12 {
    let y = yOf(90 - Double(k) * 15)
    ctx.setLineWidth(k == 6 ? 4 : 1.5)
    ctx.move(to: CGPoint(x: 0, y: y)); ctx.addLine(to: CGPoint(x: CGFloat(w), y: y)); ctx.strokePath()
}

func label(_ text: String, yaw: Double, pitch: Double, size: CGFloat, color: NSColor = .white) {
    let font = NSFont.boldSystemFont(ofSize: size)
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font, .foregroundColor: color,
        .strokeColor: NSColor.black, .strokeWidth: -3.0,
    ]
    let s = NSAttributedString(string: text, attributes: attrs)
    let sz = s.size()
    let g = NSGraphicsContext(cgContext: ctx, flipped: true)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = g
    for shift in [-360.0, 0, 360.0] {
        let cx = xOf(yaw + shift)
        if cx + sz.width / 2 < 0 || cx - sz.width / 2 > CGFloat(w) { continue }
        s.draw(at: CGPoint(x: cx - sz.width / 2, y: yOf(pitch) - sz.height / 2))
    }
    NSGraphicsContext.restoreGraphicsState()
}

let big = CGFloat(h) / 14
let names = [0: "FRONT", 90: "RIGHT", 180: "BACK", 270: "LEFT"]
for k in stride(from: 0, to: 360, by: 30) {
    let yaw = Double(k > 180 ? k - 360 : k)
    if let n = names[k] {
        label(n + (eye.isEmpty ? "" : " " + eye), yaw: yaw, pitch: 7, size: big)
    }
    label("\(k)°", yaw: yaw, pitch: -5, size: big * 0.55)
}
for p in [-60, -30, 30, 60] { label("\(p > 0 ? "+" : "")\(p)°", yaw: 15, pitch: Double(p), size: big * 0.45) }
label("UP" + (eye.isEmpty ? "" : " " + eye), yaw: 0, pitch: 78, size: big * 0.8, color: .yellow)
label("DOWN" + (eye.isEmpty ? "" : " " + eye), yaw: 0, pitch: -78, size: big * 0.8, color: .yellow)
if !eye.isEmpty { label("EYE " + eye, yaw: 0, pitch: 30, size: big * 1.4, color: eye == "L" ? .cyan : .magenta) }

let image = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: image)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
