/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.fusiondb.client;

public final class FusionHeaders
{
    public static final String PRESTO_USER = "X-Trino-User";
    public static final String PRESTO_SOURCE = "X-Trino-Source";
    public static final String PRESTO_CATALOG = "X-Trino-Catalog";
    public static final String PRESTO_SCHEMA = "X-Trino-Schema";
    public static final String PRESTO_TIME_ZONE = "X-Trino-Time-Zone";
    public static final String PRESTO_LANGUAGE = "X-Trino-Language";
    public static final String PRESTO_TRACE_TOKEN = "X-Trino-Trace-Token";
    public static final String PRESTO_SESSION = "X-Trino-Session";
    public static final String PRESTO_SET_CATALOG = "X-Trino-Set-Catalog";
    public static final String PRESTO_SET_SCHEMA = "X-Trino-Set-Schema";
    public static final String PRESTO_SET_SESSION = "X-Trino-Set-Session";
    public static final String PRESTO_CLEAR_SESSION = "X-Trino-Clear-Session";
    public static final String PRESTO_SET_ROLE = "X-Trino-Set-Role";
    public static final String PRESTO_ROLE = "X-Trino-Role";
    public static final String PRESTO_PREPARED_STATEMENT = "X-Trino-Prepared-Statement";
    public static final String PRESTO_ADDED_PREPARE = "X-Trino-Added-Prepare";
    public static final String PRESTO_DEALLOCATED_PREPARE = "X-Trino-Deallocated-Prepare";
    public static final String PRESTO_TRANSACTION_ID = "X-Trino-Transaction-Id";
    public static final String PRESTO_STARTED_TRANSACTION_ID = "X-Trino-Started-Transaction-Id";
    public static final String PRESTO_CLEAR_TRANSACTION_ID = "X-Trino-Clear-Transaction-Id";
    public static final String PRESTO_CLIENT_INFO = "X-Trino-Client-Info";
    public static final String PRESTO_CLIENT_TAGS = "X-Trino-Client-Tags";
    public static final String PRESTO_RESOURCE_ESTIMATE = "X-Trino-Resource-Estimate";
    public static final String PRESTO_EXTRA_CREDENTIAL = "X-Trino-Extra-Credential";

    public static final String PRESTO_CURRENT_STATE = "X-Trino-Current-State";
    public static final String PRESTO_MAX_WAIT = "X-Trino-Max-Wait";
    public static final String PRESTO_MAX_SIZE = "X-Trino-Max-Size";
    public static final String PRESTO_TASK_INSTANCE_ID = "X-Trino-Task-Instance-Id";
    public static final String PRESTO_PAGE_TOKEN = "X-Trino-Page-Sequence-Id";
    public static final String PRESTO_PAGE_NEXT_TOKEN = "X-Trino-Page-End-Sequence-Id";
    public static final String PRESTO_BUFFER_COMPLETE = "X-Trino-Buffer-Complete";

    private FusionHeaders() {}
}
