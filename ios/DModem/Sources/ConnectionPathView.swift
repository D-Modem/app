// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import DModemKit
import DModemShared
import SwiftUI

/// The connection path at the top of Settings, as PCOMM's "Connection
/// Overview" draws its link: each step's role above its icon, what it is
/// below, one line joining them (the shared Kotlin's ConnectionPath, as the
/// Android app has it). Idle, how it's set up (a step still to set in red,
/// its line dashed); dialling and online, how it is now.
struct ConnectionPathView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var jobs: JobsModel

    var body: some View {
        let path = Self.path(model: model, internet: jobs.internet?.connected == true)
        VStack(spacing: 8) {
            HStack(alignment: .top, spacing: 0) {
                ForEach(Array(path.steps.enumerated()), id: \.offset) { i, step in
                    StepView(
                        step: step,
                        left: i > 0 ? path.link(i: Int32(i - 1)) : nil,
                        right: i < path.steps.count - 1 ? path.link(i: Int32(i)) : nil
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            Text(path.headline)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(path.steps.contains { $0.state == ConnectionPath.State.setup } ? Color.red : Color.secondary)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(path.spoken)
    }

    /// the path as the settings are, and the modem now
    static func path(model: AppModel, internet: Bool) -> ConnectionPath.Path {
        let s = model.settings
        let line: ConnectionPath.Line
        switch s.line {
        case .sip: line = .sip
        case .wifi: line = .wifi
        case .none: line = ConnectionPath.Line.none
        }
        let call: ConnectionPath.Call
        switch model.status.link {
        case .idle: call = .idle
        case .connecting: call = .connecting
        case .online: call = .online
        case .hangingUp: call = .hangingUp
        }
        let st = model.status
        let rate = st.link != .online || (st.rxRate <= 0 && st.txRate <= 0) ? ""
            : st.rxRate == st.txRate ? "\(st.rxRate) bit/s" : "\(st.rxRate)/\(st.txRate) bit/s"
        let config = ConnectionPath.Config(
            line: line, sipAnonymous: s.anonymous, sipUser: s.user, sipDomain: s.domain, listen: s.listen,
            peerName: "", peerAddress: s.peerAddress, btModemName: "", btModemAddress: "",
            serialBaud: 115200, serialRtsCts: true, usbDevice: nil, cellSpeed: "",
            isp: !s.ispNumber.isEmpty, dialIn: false, bridges: []
        )
        let live = ConnectionPath.Live(
            run: model.run == .running ? .running : .stopped, call: call, rate: rate, internet: internet
        )
        return ConnectionPath.shared.of(c: config, live: live)
    }
}

/// a step: its role, its icon on the line (its links to either side), its name and what it is
private struct StepView: View {
    let step: ConnectionPath.Step
    let left: ConnectionPath.State?
    let right: ConnectionPath.State?

    var body: some View {
        VStack(spacing: 4) {
            Text(step.role).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            ZStack {
                // (the line through the icons' middles: half to the step before, half to the next)
                GeometryReader { g in
                    let y = g.size.height / 2
                    if let left {
                        Path { p in p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: g.size.width / 2, y: y)) }
                            .stroke(Self.color(left), style: Self.stroke(left))
                    }
                    if let right {
                        Path { p in p.move(to: CGPoint(x: g.size.width / 2, y: y)); p.addLine(to: CGPoint(x: g.size.width, y: y)) }
                            .stroke(Self.color(right), style: Self.stroke(right))
                    }
                }
                Circle()
                    .fill(step.state == ConnectionPath.State.active ? Self.color(step.state).opacity(0.2) : Color(.systemBackground))
                    .overlay(Circle().stroke(Self.color(step.state), lineWidth: 2))
                    .frame(width: 44, height: 44)
                Image(systemName: Self.symbol(step.kind))
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Self.color(step.state))
            }
            .frame(height: 44)
            Text(step.title).font(.caption.weight(.semibold)).multilineTextAlignment(.center).lineLimit(2)
            Text(step.detail)
                .font(.caption2)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .foregroundStyle(step.state == ConnectionPath.State.setup ? Color.red : Color.secondary)
        }
        .padding(.horizontal, 2)
    }

    /// a state's colour: ready the accent, to set red, off faint, dialling and online green
    static func color(_ s: ConnectionPath.State) -> Color {
        if s == ConnectionPath.State.setup { return .red }
        if s == ConnectionPath.State.off { return Color(.tertiaryLabel) }
        if s == ConnectionPath.State.working || s == ConnectionPath.State.active { return .green }
        return .accentColor
    }

    /// a link's line: dashed where a step is still to set, or off
    static func stroke(_ s: ConnectionPath.State) -> StrokeStyle {
        let dashed = s == ConnectionPath.State.setup || s == ConnectionPath.State.off
        return StrokeStyle(lineWidth: 2, dash: dashed ? [6, 4] : [])
    }

    /// a step's SF Symbol
    static func symbol(_ k: ConnectionPath.Kind) -> String {
        switch k {
        case .terminal: return "terminal"
        case .ppp: return "globe"
        case .softmodem, .modem: return "server.rack"
        case .serial, .usb: return "cable.connector"
        case .bluetooth: return "dot.radiowaves.left.and.right"
        case .wifi: return "wifi"
        case .sip, .cell: return "phone.arrow.up.right"
        case .sipAddress: return "at"
        case .phoneNetwork, .phoneLine: return "phone"
        case .phone: return "iphone"
        default: return "xmark"
        }
    }
}
