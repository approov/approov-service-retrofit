//
// MIT License
// 
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.retrofit;

import okhttp3.Request;

/**
 * ApproovInterceptorExtensions provides an interface for handling callbacks during
 * the processing of network requests by Approov. It allows further modifications
 * to requests after Approov has applied its changes.
 */
public interface ApproovInterceptorExtensions {

    /**
     * Called after Approov has processed a network request, allowing further modifications.
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the modified request
     * @throws ApproovException if there is an error during processing
     */
    Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException;
}
