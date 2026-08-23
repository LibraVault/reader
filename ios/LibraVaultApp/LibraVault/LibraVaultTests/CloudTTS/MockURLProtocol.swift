import Foundation

/// Hand-rolled `URLProtocol` mock for vendor adapter tests — the plan's chosen
/// alternative to a real network dependency (this repo has none — see the
/// implementation plan's "Vendor adapters" section), and to Android's
/// `MockWebServer`/`okhttp3.mockwebserver`, which has no Swift/iOS equivalent.
///
/// Intercepts every request made through a `URLSession` configured with
/// `MockURLProtocol.makeSession()` and returns whatever `requestHandler` was set to
/// return, recording the request that arrived so tests can assert on headers/body/URL.
final class MockURLProtocol: URLProtocol {
    struct StubbedResponse {
        let statusCode: Int
        let body: Data
        let headers: [String: String]

        init(statusCode: Int, body: Data = Data(), headers: [String: String] = [:]) {
            self.statusCode = statusCode
            self.body = body
            self.headers = headers
        }
    }

    /// Set by each test before making a request. `nil` (never set, or left over from a
    /// prior test that didn't `reset()`) fails the request loudly via
    /// `didFailWithError` rather than silently returning an empty 200 — a test that
    /// forgets to stub a handler should see a clear failure, not a confusing false pass.
    static var requestHandler: ((URLRequest) -> StubbedResponse)?
    private(set) static var lastRequest: URLRequest?

    /// A fresh, ephemeral session routed entirely through this mock — never touches
    /// the real network.
    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: config)
    }

    static func reset() {
        requestHandler = nil
        lastRequest = nil
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        guard let handler = Self.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        let stub = handler(request)
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: stub.statusCode, httpVersion: "HTTP/1.1", headerFields: stub.headers)
        else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

extension URLRequest {
    /// `URLSession` sometimes materializes `httpBody` into `httpBodyStream` internally
    /// before handing the request to a `URLProtocol` — a well-known testing gotcha
    /// (the body silently reads back as `nil` via `.httpBody` even though the request
    /// genuinely has one). This reads whichever is actually present, so tests can
    /// assert on the real bytes sent either way.
    func capturedHTTPBody() -> Data? {
        if let httpBody { return httpBody }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: bufferSize)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return data
    }
}
