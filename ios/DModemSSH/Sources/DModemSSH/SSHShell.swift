// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
import Foundation
import NIOCore
import NIOPosix
import NIOSSH

/// An SSH login's interactive shell: the host's key checked by the caller
/// ([verifyHostKey], its OpenSSH form), the user logged in by password
/// ([askPassword], asked again when one is refused, three times at most),
/// a pty of [term] in the screen's size and a shell on it. What the
/// server says (stdout and stderr) goes to [onData]; [write] sends what
/// is typed; [resize] the window's new size. [onClose] once, with why
/// (nil when it was asked to).
///
/// The callbacks are made on the connection's own thread: hop to the
/// main one to touch views. Host keys: Ed25519 and ECDSA (SwiftNIO SSH
/// has no RSA).
public final class SSHShell: @unchecked Sendable {
    public let host: String
    public let port: Int
    public let user: String
    public let term: String

    public var onData: (Data) -> Void = { _ in }
    public var onClose: (String?) -> Void = { _ in }
    /// the host's key ("ssh-ed25519 AAAA..."): call back with true to go on
    public var verifyHostKey: (String, @escaping (Bool) -> Void) -> Void = { _, ok in ok(false) }
    /// the user's password, asked ("" when it's the first time, else why
    /// the last was refused): call back with nil to give up
    public var askPassword: (String, @escaping (String?) -> Void) -> Void = { _, done in done(nil) }

    private var cols: Int
    private var rows: Int
    /// (SwiftNIO's shared threads: a shell's owner may let go of it on one)
    private let group = MultiThreadedEventLoopGroup.singleton
    private var channel: Channel?
    private var shell: Channel?
    private let lock = NSLock()
    private var closed = false
    private var closing = false
    private var exitStatus: Int?

    public init(host: String, port: Int = 22, user: String, term: String = "xterm-256color", cols: Int = 80, rows: Int = 24) {
        self.host = host
        self.port = port
        self.user = user
        self.term = term
        self.cols = cols
        self.rows = rows
    }

    /// connect, log in and start the shell, on the connection's thread
    public func connect() {
        let auth = PasswordAuth(user: user) { [weak self] why, done in
            guard let self else { return done(nil) }
            self.askPassword(why, done)
        }
        let keys = HostKeyCheck { [weak self] key, done in
            guard let self else { return done(false) }
            self.verifyHostKey(key, done)
        }
        let bootstrap = ClientBootstrap(group: group)
            .connectTimeout(.seconds(20))
            .channelOption(ChannelOptions.socket(IPPROTO_TCP, TCP_NODELAY), value: 1)
            .channelInitializer { ch in
                ch.eventLoop.makeCompletedFuture {
                    let ssh = NIOSSHHandler(
                        role: .client(.init(userAuthDelegate: auth, serverAuthDelegate: keys)),
                        allocator: ch.allocator, inboundChildChannelInitializer: nil)
                    try ch.pipeline.syncOperations.addHandlers([ssh, ErrorCatcher { [weak self] e in self?.failed(e) }])
                }
            }
        bootstrap.connect(host: host, port: port).flatMap { [weak self] (ch: Channel) -> EventLoopFuture<Channel> in
            guard let self else { return ch.eventLoop.makeFailedFuture(ChannelError.ioOnClosedChannel) }
            self.lock.withLock { self.channel = ch }
            ch.closeFuture.whenComplete { [weak self] _ in self?.finish(nil) }
            return ch.pipeline.handler(type: NIOSSHHandler.self).flatMap { ssh in
                let p = ch.eventLoop.makePromise(of: Channel.self)
                ssh.createChannel(p) { child, type in
                    guard type == .session else {
                        return child.eventLoop.makeFailedFuture(SSHShellError.unexpectedChannel)
                    }
                    return child.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).flatMap {
                        child.pipeline.addHandler(ShellHandler(owner: self))
                    }
                }
                return p.futureResult
            }
        }.flatMap { [weak self] (child: Channel) -> EventLoopFuture<Void> in
            guard let self else { return child.eventLoop.makeSucceededVoidFuture() }
            self.lock.withLock { self.shell = child }
            let (c, r) = self.lock.withLock { (self.cols, self.rows) }
            let pty = SSHChannelRequestEvent.PseudoTerminalRequest(
                wantReply: true, term: self.term, terminalCharacterWidth: c, terminalRowHeight: r,
                terminalPixelWidth: 0, terminalPixelHeight: 0, terminalModes: SSHTerminalModes([:]))
            return child.triggerUserOutboundEvent(pty).flatMap {
                child.triggerUserOutboundEvent(SSHChannelRequestEvent.ShellRequest(wantReply: true))
            }
        }.whenFailure { [weak self] e in self?.failed(e) }
    }

    /// what's typed, to the shell
    public func write(_ data: Data) {
        guard let s = lock.withLock({ shell }), !data.isEmpty else { return }
        var b = s.allocator.buffer(capacity: data.count)
        b.writeBytes(data)
        s.writeAndFlush(SSHChannelData(type: .channel, data: .byteBuffer(b)), promise: nil)
    }

    /// the screen's new size, to the server
    public func resize(cols: Int, rows: Int) {
        let s: Channel? = lock.withLock {
            guard cols != self.cols || rows != self.rows else { return nil }
            self.cols = cols
            self.rows = rows
            return shell
        }
        s?.triggerUserOutboundEvent(SSHChannelRequestEvent.WindowChangeRequest(
            terminalCharacterWidth: cols, terminalRowHeight: rows, terminalPixelWidth: 0, terminalPixelHeight: 0), promise: nil)
    }

    /// the connection closed by the user
    public func close() {
        let ch: Channel? = lock.withLock {
            closing = true
            return channel
        }
        if let ch {
            ch.close(promise: nil)
        } else {
            finish(nil)
        }
    }

    fileprivate func received(_ data: Data) { onData(data) }

    fileprivate func exited(_ status: Int) { lock.withLock { exitStatus = status } }

    fileprivate func shellClosed() {
        // (the shell's over: so is the connection)
        lock.withLock { channel }?.close(promise: nil)
    }

    fileprivate func failed(_ e: Error) {
        finish(Self.describe(e))
        lock.withLock { channel }?.close(promise: nil)
    }

    private func finish(_ why: String?) {
        let (first, asked, status): (Bool, Bool, Int?) = lock.withLock {
            defer { closed = true }
            return (!closed, closing, exitStatus)
        }
        guard first else { return }
        if asked {
            onClose(nil)
        } else if let why {
            onClose(why)
        } else if let status, status != 0 {
            onClose("the shell exited with status \(status)")
        } else {
            onClose("Connection closed")
        }
    }

    static func describe(_ e: Error) -> String {
        switch e {
        case SSHShellError.hostKeyRefused: return "the host's key was not accepted"
        case SSHShellError.noPassword: return "not logged in"
        case SSHShellError.unexpectedChannel: return "the server opened an unexpected channel"
        default: break
        }
        if let n = e as? NIOSSHError {
            switch n.type {
            case .invalidHostKeyForKeyExchange, .keyExchangeNegotiationFailure:
                return "no host key or key exchange in common (this app has Ed25519 and ECDSA, not RSA)"
            default:
                break
            }
        }
        if e is NIOConnectionError || e is IOError { return "could not connect: \(e)" }
        return "\(e)"
    }
}

public enum SSHShellError: Error {
    case hostKeyRefused, noPassword, unexpectedChannel
}

/// the password, asked; again (with why) when the server refuses it, three times at most
private final class PasswordAuth: NIOSSHClientUserAuthenticationDelegate {
    let user: String
    let ask: (String, @escaping (String?) -> Void) -> Void
    var tries = 0

    init(user: String, ask: @escaping (String, @escaping (String?) -> Void) -> Void) {
        self.user = user
        self.ask = ask
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard availableMethods.contains(.password) else {
            nextChallengePromise.fail(SSHShellError.noPassword)
            return
        }
        guard tries < 3 else {
            nextChallengePromise.fail(SSHShellError.noPassword)
            return
        }
        tries += 1
        let user = self.user
        ask(tries == 1 ? "" : "The password was not accepted") { pw in
            guard let pw else {
                nextChallengePromise.fail(SSHShellError.noPassword)
                return
            }
            nextChallengePromise.succeed(NIOSSHUserAuthenticationOffer(
                username: user, serviceName: "", offer: .password(.init(password: pw))))
        }
    }
}

/// the host's key, as OpenSSH writes it, to the caller to accept or not
private final class HostKeyCheck: NIOSSHClientServerAuthenticationDelegate {
    let verify: (String, @escaping (Bool) -> Void) -> Void

    init(verify: @escaping (String, @escaping (Bool) -> Void) -> Void) {
        self.verify = verify
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        verify(String(openSSHPublicKey: hostKey)) { ok in
            if ok {
                validationCompletePromise.succeed(())
            } else {
                validationCompletePromise.fail(SSHShellError.hostKeyRefused)
            }
        }
    }
}

/// the shell's channel: its data (stdout and stderr) to the owner, its exit status kept
private final class ShellHandler: ChannelInboundHandler {
    typealias InboundIn = SSHChannelData

    private weak var owner: SSHShell?

    init(owner: SSHShell) {
        self.owner = owner
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let d = unwrapInboundIn(data)
        guard case .byteBuffer(var b) = d.data, let bytes = b.readBytes(length: b.readableBytes) else { return }
        owner?.received(Data(bytes))
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let s = event as? SSHChannelRequestEvent.ExitStatus {
            owner?.exited(s.exitStatus)
        } else if event is ChannelEvent, case .inputClosed = event as! ChannelEvent {
            context.close(promise: nil)
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelInactive(context: ChannelHandlerContext) {
        owner?.shellClosed()
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        owner?.failed(error)
        context.close(promise: nil)
    }
}

/// the connection's errors (a refused host key, a failed login) to the owner
private final class ErrorCatcher: ChannelInboundHandler {
    typealias InboundIn = Any
    let caught: (Error) -> Void

    init(caught: @escaping (Error) -> Void) {
        self.caught = caught
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        caught(error)
        context.close(promise: nil)
    }
}
