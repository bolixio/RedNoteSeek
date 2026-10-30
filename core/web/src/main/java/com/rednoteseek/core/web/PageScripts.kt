package com.rednoteseek.core.web

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 注入 xhs 页面的脚本，只在 [XhsUrls.ORIGIN] 下生效。 */
internal object PageScripts {

    /** 页面里的桥接对象名；[HOOK] 中按同名全局变量 `RnsBridge` 调用。 */
    const val BRIDGE_NAME = "RnsBridge"

    /**
     * 列表翻页请求（搜索结果、主页笔记、收藏、专辑）。[HOOK] 用 `window.__rnsListPending` 记录在途的这类请求，
     * [SCROLL_PAGE] 据此避免网页已在拉下一页时再滚。JS 与 Java 正则语法在这里一致，单测直接用它检查。
     */
    internal const val LIST_PAGE_PATTERN = "/api/sns/web/(v2/search/notes|v1/user_posted|v2/note/collect/page|v1/board/note)(\\?|$)"

    /**
     * document-start 注入：包装 XHR、fetch 和 WebSocket，把 /api/sns/web/、点点会话（/api/sns/agentspark/web/）、关注名单（消息模块预取）与推送 WebSocket 的业务帧原样回传给原生层。
     * 消息格式 `{url, request, status, body}`，见 [CapturedResponse.parse]。
     * 另外用 `window.__rnsListPending` 记录在途的列表翻页请求数（见 [LIST_PAGE_PATTERN]）。
     */
    val HOOK = """
        (function () {
          if (window.__rnsHooked || !window.RnsBridge) return;
          window.__rnsHooked = true;
          // 滚动上报：网页滚动时通知原生层隐藏底栏
          var lastScrollY = -1;
          window.addEventListener('scroll', function () {
            var y = window.scrollY;
            if (Math.abs(y - lastScrollY) > 2) { lastScrollY = y; try { RnsBridge.postMessage(JSON.stringify({ url: '__scroll__', request: '', status: 0, body: '' })); } catch (e) {} }
          }, { passive: true });
          var MATCH = /\/api\/sns\/web\/|\/api\/sns\/agentspark\/web\/|\/api\/im\/web\/users\/following\/all/;
          var LIST = new RegExp(${jsString(LIST_PAGE_PATTERN)});
          window.__rnsListPending = 0;
          // 列表翻页请求：发出时加一，结束（成功或失败）时减一；返回结束时要调用的函数
          function trackList(url) {
            if (!LIST.test(String(url))) return function () {};
            window.__rnsListPending++;
            var done = false;
            return function () { if (!done) { done = true; window.__rnsListPending--; } };
          }
          function report(url, request, status, body) {
            try {
              RnsBridge.postMessage(JSON.stringify({ url: String(url), request: request, status: status, body: body }));
            } catch (e) {}
          }
          var open = XMLHttpRequest.prototype.open;
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__rnsUrl = url;
            return open.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function (data) {
            var xhr = this;
            xhr.addEventListener('loadend', trackList(xhr.__rnsUrl || ''));
            if (MATCH.test(String(xhr.__rnsUrl || ''))) {
              var request = typeof data === 'string' ? data : '';
              xhr.addEventListener('loadend', function () {
                var body;
                try {
                  body = (xhr.responseType === '' || xhr.responseType === 'text')
                    ? xhr.responseText : JSON.stringify(xhr.response);
                } catch (e) { body = ''; }
                report(xhr.__rnsUrl, request, xhr.status, body);
              });
            }
            return send.apply(this, arguments);
          };
          if (window.fetch) {
            var origFetch = window.fetch;
            window.fetch = function (input, init) {
              var url = (typeof input === 'string' || input instanceof URL) ? String(input) : ((input && input.url) || '');
              var request = (init && typeof init.body === 'string') ? init.body : '';
              var listDone = trackList(url);
              var p = origFetch.apply(this, arguments);
              p.then(listDone, listDone);
              if (MATCH.test(url)) {
                p.then(function (r) {
                  return r.clone().text().then(function (t) { report(url, request, r.status, t); });
                }).catch(function () {});
              }
              return p;
            };
          }
          // 点点 AI 的流式回答只走推送 WebSocket：把收到的业务帧（t 为 4）原样回传，在原生层解码
          if (window.WebSocket) {
            var OrigWS = window.WebSocket;
            var HookedWS = function (url, protocols) {
              var ws = protocols === undefined ? new OrigWS(url) : new OrigWS(url, protocols);
              if (String(url).indexOf('apppush-rws.') >= 0) {
                ws.addEventListener('message', function (e) {
                  if (typeof e.data === 'string' && e.data.indexOf('"t":4') >= 0) report(url, '', 101, e.data);
                });
              }
              return ws;
            };
            HookedWS.prototype = OrigWS.prototype;
            HookedWS.CONNECTING = 0; HookedWS.OPEN = 1; HookedWS.CLOSING = 2; HookedWS.CLOSED = 3;
            window.WebSocket = HookedWS;
          }
          // App 自己用 ExoPlayer 播放视频：网页里的媒体一律静音并暂停，避免隐藏页面里出声、耗流量
          document.addEventListener('play', function (e) {
            var t = e.target;
            if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
              try { t.muted = true; t.pause(); } catch (err) {}
            }
          }, true);
        })();
    """.trimIndent()

    /**
     * 分步滚动整页（8 × 800px，间隔 250ms）。一次性跳到底部不会触发页面懒加载（真机验证结论）。
     * 打开笔记时网页会被滚回卡片所在位置，离底部可能远超 6400px（真机：停在顶部、距底 9789px，连续两次翻页无效），
     * 所以先跳到距底部约 3 屏处，再分步滚完最后一段。
     * 只拉一页：网页已在拉下一页（`window.__rnsListPending` > 0，见 [HOOK]）时不滚；滚动中一出现在途请求就停。
     * 否则新的一页接上后继续滚会再碰到底部，网页又拉一页（真机：第一页较短时网页会自己补拉一页，
     * App 同时翻页、脚本照常滚满 8 步，一次连拉 3–4 页）。
     * AI 结果页滚动的是 `.ai-feeds-page` 容器而不是整页。
     */
    val SCROLL_PAGE = """
        (function () {
          function loading() { return (window.__rnsListPending || 0) > 0; }
          if (loading()) return;
          var sc = document.querySelector('.ai-feeds-page');
          function y() { return sc ? sc.scrollTop : window.scrollY; }
          function to(v) { if (sc) sc.scrollTop = v; else window.scrollTo(0, v); }
          var max = sc ? sc.scrollHeight - sc.clientHeight : document.documentElement.scrollHeight - window.innerHeight;
          var start = max - 2400;
          if (start > y()) to(start);
          var n = 0;
          var t = setInterval(function () {
            if (loading() || n >= 8) { clearInterval(t); return; }
            to(y() + 800);
            n++;
          }, 250);
        })()
    """.trimIndent()

    private val NOTE_ID = Regex("^[0-9a-zA-Z]{1,64}$")

    /** 笔记 ID 会拼进脚本，只接受字母数字，防止脚本注入。 */
    fun isValidNoteId(noteId: String): Boolean = NOTE_ID.matches(noteId)

    /**
     * 读出页面状态里缓存的评论（`window.__INITIAL_STATE__.note.noteDetailMap[noteId].comments`，Vue ref），
     * 转成与 v2/comment/page 的 data 相同的 snake_case JSON；取不到或列表为空时返回空串。
     * 网页对打开过的笔记有缓存，再次打开时不会重新请求评论接口。
     */
    fun readCachedComments(noteId: String): String = """
        (function () {
          try {
            function raw(r) { return r && (r._rawValue !== undefined ? r._rawValue : (r._value !== undefined ? r._value : r)); }
            var map = raw(window.__INITIAL_STATE__.note.noteDetailMap);
            var detail = raw(map["$noteId"]);
            var c = raw(detail && detail.comments);
            if (!c || !c.list || !c.list.length) return '';
            function user(u) { u = u || {}; return { user_id: u.userId || '', nickname: u.nickname || '', image: u.image || '' }; }
            function conv(x) {
              return {
                id: x.id || '', note_id: x.noteId || '', content: x.content || '',
                like_count: String(x.likeCount || '0'), ip_location: x.ipLocation || '',
                create_time: x.createTime || 0,
                target_comment: x.targetComment ? { id: x.targetComment.id || '', user_info: user(x.targetComment.userInfo) } : null,
                sub_comment_count: String(x.subCommentCount || '0'), sub_comment_has_more: !!x.subCommentHasMore,
                user_info: user(x.userInfo),
                sub_comments: (x.subComments || []).map(conv)
              };
            }
            return JSON.stringify({ comments: c.list.map(conv), cursor: c.cursor || '', has_more: !!c.hasMore });
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /**
     * 汇报目标卡片是否在页面上，以及当前所有卡片的绝对位置；返回 JSON 字符串，见 [CardSnapshot.parse]。
     * 点点 AI 的参考来源抽屉打开时也在抽屉里找（抽屉卡片没有 data-note-id，按封面链接匹配）。
     * AI 结果页的滚动位置取 `.ai-feeds-page` 容器的。
     */
    fun reportCards(noteId: String): String = """
        (function () {
          var sc = document.querySelector('.ai-feeds-page');
          var y = sc ? sc.scrollTop : window.scrollY;
          var found = !!document.querySelector('.drawer section.note-item a.cover[href*="/$noteId?"]') ||
            !!document.querySelector('section.note-item[data-note-id="$noteId"] a.cover');
          var cards = [].slice.call(document.querySelectorAll('section.note-item')).map(function (s) {
            return { id: s.getAttribute('data-note-id') || '', top: Math.round(s.getBoundingClientRect().top + y) };
          });
          return JSON.stringify({ found: found, scrollY: Math.round(y), viewport: sc ? sc.clientHeight : window.innerHeight, cards: cards });
        })()
    """.trimIndent()

    /**
     * 点击卡片封面，打开笔记弹层（与真人点击走同一路径）。返回 clicked / pending / missing。
     * 服务端渲染的卡片在网页脚本接管（Vue 挂载到 #app、设置 __vue_app__）之前就已出现，
     * 这时点击会按普通链接整页跳到笔记地址：没有 v1/feed、详情超时，且等同直接加载笔记链接（461 风险）。
     * 所以未接管时返回 pending，不点（真机：作者主页加载完约 100ms 就点击时复现）。
     * 参考来源抽屉开着时优先点抽屉里的卡片（先滚进可见区）。
     */
    fun clickCard(noteId: String): String = """
        (function () {
          var d = document.querySelector('.drawer section.note-item a.cover[href*="/$noteId?"]');
          var a = d || document.querySelector('section.note-item[data-note-id="$noteId"] a.cover');
          if (!a) return 'missing';
          var app = document.querySelector('#app');
          if (!app || !app.__vue_app__) return 'pending';
          if (d) d.scrollIntoView({ block: 'center' });
          a.click();
          return 'clicked';
        })()
    """.trimIndent()

    fun scrollTo(y: Int): String =
        "(function () { var sc = document.querySelector('.ai-feeds-page'); if (sc) sc.scrollTop = $y; else window.scrollTo(0, $y); return 'ok'; })()"

    /** 关闭笔记弹层：只在弹层存在时动作，避免把搜索页后退掉。优先点关闭按钮，没有再后退。 */
    val CLOSE_NOTE = """
        (function () {
          if (!document.querySelector('.note-detail-mask')) return 'none';
          var c = document.querySelector('.close-circle');
          if (c) { c.click(); return 'clicked'; }
          history.back();
          return 'back';
        })()
    """.trimIndent()

    /**
     * 在笔记弹层里分步滚动（8 × 600px，间隔 250ms），触发评论翻页。
     * 宽屏布局滚动的是 .note-scroller，窄屏布局（WebView 宽 360）滚动的是外层 #noteContainer，
     * 取其中真正可滚动的那个（真机验证两种布局都能触发 v2/comment/page）。
     */
    val SCROLL_COMMENTS = """
        (function () {
          var el = ['.note-scroller', '#noteContainer'].map(function (s) { return document.querySelector(s); })
            .filter(function (e) { return e && e.scrollHeight > e.clientHeight + 10; })[0];
          if (!el) return 'none';
          var n = 0;
          var t = setInterval(function () {
            el.scrollBy(0, 600);
            if (++n >= 8) clearInterval(t);
          }, 250);
          return 'ok';
        })()
    """.trimIndent()

    /**
     * 已停在搜索结果页时换关键词：往网页搜索框填词并派发 keypress Enter（与真人同路径），
     * 网页走 SPA 内导航、只发 v2/search/notes，不整页重载（真机：2.8s → 0.9–1.5s）。
     * 页面只监听 keypress，keydown / keyup 会被静默忽略；赋值要用原生 setter，Vue 才能收到 input 事件。
     * 只在 /search_result 上用：从 /explore 的搜索框搜会进未验证的 /search_result_ai。
     * 笔记弹层开着也可以直接搜，网页会自己关掉弹层。
     * 返回 sent / elsewhere（不在结果页）/ pending（网页脚本未接管）/ missing。
     */
    fun searchInPage(keyword: String): String = """
        (function () {
          try {
          var p = location.pathname;
          if (p !== '/search_result' && p !== '/search_result/') return 'elsewhere';
          var app = document.querySelector('#app');
          if (!app || !app.__vue_app__) return 'pending';
          var i = document.querySelector('#search-input');
          if (!i) return 'missing';
          i.focus();
          Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, ${jsString(keyword)});
          i.dispatchEvent(new Event('input', { bubbles: true }));
          i.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
          return 'sent';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * AI 模式的页内换词：AI 结果页与 /explore 的搜索框都是 textarea#search-input-ai，回车后进入（或停留在）AI 结果页，
     * SPA 内导航约 0.5–0.8s（真机）。与 [searchInPage] 不同，这里 keydown / keypress / keyup 三个都派发。
     * 返回 sent / elsewhere（不在这两个页面）/ pending（网页脚本未接管）/ missing。
     */
    fun aiSearchInPage(keyword: String): String = """
        (function () {
          try {
          var p = location.pathname;
          if (p !== '/search_result_ai' && p !== '/search_result_ai/' && p !== '/explore' && p !== '/explore/') return 'elsewhere';
          var app = document.querySelector('#app');
          if (!app || !app.__vue_app__) return 'pending';
          var i = document.querySelector('#search-input-ai');
          if (!i) return 'missing';
          i.focus();
          Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(i, ${jsString(keyword)});
          i.dispatchEvent(new Event('input', { bubbles: true }));
          ['keydown', 'keypress', 'keyup'].forEach(function (t) {
            i.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
          });
          return 'sent';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 当前结果页 URL 里的关键词，用来确认 [searchInPage] 之后 SPA 导航真的发生了。
     * 网页会把关键词二次编码（`keyword=%25E5%2592%2596…`），URLSearchParams 取出后仍含 % 时再解码一次。
     * 不在结果页或解码失败返回空串。
     */
    val SEARCH_KEYWORD = keywordOn("/search_result")

    /** 同 [SEARCH_KEYWORD]，只认 AI 结果页，用于确认 [aiSearchInPage] 的导航。 */
    val AI_SEARCH_KEYWORD = keywordOn("/search_result_ai")

    private fun keywordOn(path: String): String = """
        (function () {
          try {
          var p = location.pathname;
          if (p !== '$path' && p !== '$path/') return '';
          var k = new URLSearchParams(location.search).get('keyword') || '';
          if (k.indexOf('%') >= 0) { try { k = decodeURIComponent(k); } catch (e2) { return ''; } }
          return k;
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /** 打开搜索结果页的「筛选」面板；已打开则不动。返回 opened / already / missing。 */
    val OPEN_SEARCH_FILTER = """
        (function () {
          var g = [].slice.call(document.querySelectorAll('.filters')).filter(function (x) { return (x.textContent || '').indexOf('排序依据') >= 0; })[0];
          if (g) return 'already';
          var f = [].slice.call(document.querySelectorAll('span')).filter(function (s) { return (s.textContent || '').trim() === '筛选'; })[0];
          if (!f) return 'missing';
          (f.closest('div') || f).click();
          return 'opened';
        })()
    """.trimIndent()

    /** 点击「排序依据」里的某个标签；网页随即重新请求第一页。返回 clicked / no-group / no-tag。 */
    fun pickSearchSort(sort: SearchSort): String = """
        (function () {
          var g = [].slice.call(document.querySelectorAll('.filters')).filter(function (x) { return (x.textContent || '').indexOf('排序依据') >= 0; })[0];
          if (!g) return 'no-group';
          var t = [].slice.call(g.querySelectorAll('.tags')).filter(function (x) { return x.textContent.trim() === '${sort.label}'; })[0];
          if (!t) return 'no-tag';
          t.click();
          return 'clicked';
        })()
    """.trimIndent()

    /**
     * 点 AI 总结卡片上的「ai总结N篇笔记生成」，打开网页的「参考来源」抽屉（网页随即请求 v1/search/dqa/detail，
     * 同一页面第二次打开走缓存）。返回 open（已打开）/ clicked / missing。
     */
    val OPEN_SUMMARY_SOURCES = """
        (function () {
          try {
          if (document.querySelector('.drawer section.note-item')) return 'open';
          var s = [].slice.call(document.querySelectorAll('span.progress-text')).filter(function (e) {
            return /^ai总结\d+篇笔记生成$/.test((e.textContent || '').trim());
          });
          var t = s[s.length - 1];
          if (!t) return 'missing';
          t.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /** 关闭「参考来源」抽屉。返回 clicked / none。 */
    val CLOSE_SUMMARY_SOURCES = """
        (function () {
          var c = document.querySelector('.drawer .icon-close');
          if (!c) return 'none';
          c.click();
          return 'clicked';
        })()
    """.trimIndent()

    /**
     * 点点对话页：往输入框填问题。会话中是 `.input-container textarea`，欢迎页是 `.ai-chat-welcome__input textarea`。
     * 赋值要用原生 setter 再派发 input 事件，网页才会把发送按钮变成可点（见 [CHAT_SUBMIT]）。
     * 旅游攻略技能模式（自动触发）下输入框换成 contenteditable 的 `.input-container .xhs-travel-rich-input`，
     * 没有 textarea 时改用它：设 textContent 再派发 InputEvent。
     * 已标记过期（[MARK_STALE]，新页面还没加载上来）的旧文档返回 missing。返回 filled / missing。
     */
    fun chatFill(text: String): String = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          var ta = document.querySelector('.input-container textarea') || document.querySelector('.ai-chat-welcome__input textarea');
          var t = ${jsString(text)};
          if (ta) {
            ta.focus();
            Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(ta, t);
            ta.dispatchEvent(new Event('input', { bubbles: true }));
            return 'filled';
          }
          // 旅游攻略技能模式：可编辑 div
          var rich = document.querySelector('.input-container .xhs-travel-rich-input');
          if (!rich) return 'missing';
          rich.focus();
          rich.textContent = t;
          rich.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: t }));
          return 'filled';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 点发送：会话中是 `.input-container .submit-button-wrapper`（不带 disabled），欢迎页是 `.wendian-btn--sendable`。
     * 生成中同一个按钮会变成停止（`pause-mode`），这里绝不点它。旧文档（[MARK_STALE]）返回 pending。
     * 返回 sent / pending（按钮还没变成可点）。
     */
    val CHAT_SUBMIT = """
        (function () {
          try {
          if (window.__rnsStale) return 'pending';
          var b = document.querySelector('.input-container .submit-button-wrapper:not(.disabled):not(.pause-mode)') ||
            document.querySelector('.wendian-btn--sendable');
          if (!b) return 'pending';
          b.click();
          return 'sent';
          } catch (e) { return 'pending'; }
        })()
    """.trimIndent()

    /** 停止生成：生成中发送按钮变为 `.submit-button-wrapper.pause-mode`，点它网页经推送 WebSocket 发 user.message.cancel。返回 stopped / none。 */
    val CHAT_STOP = """
        (function () {
          try {
          var p = document.querySelector('.submit-button-wrapper.pause-mode');
          if (!p) return 'none';
          p.click();
          return 'stopped';
          } catch (e) { return 'none'; }
        })()
    """.trimIndent()

    /**
     * 在对话页侧边栏删除第 [index] 个会话（与刚加载的 conversation/list 同序，不计分组标题），点之前核对标题防止删错。
     * 侧边栏没展开时先展开：要在 `svg.xhs-ai-history-icon-btn` 上派发 pointer / mouse 事件（单纯 click() 不行），
     * 1.5 秒内不重复派发，免得把正在展开的侧边栏又收起；这时返回 loading，调用方稍后重试。
     * 网页的 × 没有确认框，点了就删（App 里先确认）。加载新页面前打过 [MARK_STALE] 的旧文档一律返回 loading（不在旧页面上删）。
     * 返回 clicked / loading / mismatch / missing。
     */
    fun chatDelete(index: Int, title: String): String = """
        (function () {
          try {
          if (window.__rnsStale) return 'loading';
          if (!document.querySelector('.xhs-ai-history-expanded')) {
            var s = document.querySelector('svg.xhs-ai-history-icon-btn');
            if (!s) return 'loading';
            if (!window.__rnsHistoryAt || Date.now() - window.__rnsHistoryAt > 1500) {
              window.__rnsHistoryAt = Date.now();
              ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
                s.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
              });
            }
            return 'loading';
          }
          var items = document.querySelectorAll('.xhs-ai-history-item');
          if (items.length <= $index) return 'loading';
          var it = items[$index];
          var t = it.querySelector('.xhs-ai-history-item-title');
          if (!t || t.textContent.trim() !== ${jsString(title.trim())}) return 'mismatch';
          var x = it.querySelector('.xhs-ai-history-item-close');
          if (!x) return 'missing';
          x.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 打开第 [answerIndex] 条回答（`.ai-message`，与 App 里的回答同序）的参考来源抽屉：对话回答点
     * 「搜索并总结N人真实经验」（`button.xhs-v3-tool-call-capsule`），搜索总结点「ai总结N篇笔记生成」
     * （`.xhs-spark-source-header-text`；取不到时按文字找，spike 里这样点到过）。
     * 网页随即请求 complex/source 或 dqa/detail。抽屉已打开返回 open；否则 clicked / missing。
     */
    fun chatOpenSources(answerIndex: Int): String = """
        (function () {
          try {
          if (document.querySelector('.drawer section.note-item')) return 'open';
          var m = document.querySelectorAll('.ai-message')[$answerIndex];
          if (!m) return 'missing';
          // 只认「搜索并总结N人真实经验」：旅行技能的回答里同类按钮是「思考完成」（展开思考过程，没有来源抽屉）
          var t = [].slice.call(m.querySelectorAll('button.xhs-v3-tool-call-capsule')).filter(function (b) {
              return /总结\d+人/.test(b.textContent || '');
            })[0] || m.querySelector('.xhs-spark-source-header-text') ||
            [].slice.call(m.querySelectorAll('*')).filter(function (e) {
              return e.children.length === 0 && /^ai总结\d+篇笔记生成$/.test((e.textContent || '').trim());
            })[0];
          if (!t) return 'missing';
          t.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 主引擎：在 AI 结果页点「问点点」标签（`.channel`）。网页随即 SPA 跳到
     * `/ai_chat_tab?from=searchTab&searchKeyWord=<kw>&conversationId=<新会话>`，并发 search/signal 把当前总结挂进这个会话。
     * 不在 AI 结果页或没有标签返回 missing，否则 clicked。
     */
    val ASK_TAB = """
        (function () {
          try {
          var p = location.pathname;
          if (p !== '/search_result_ai' && p !== '/search_result_ai/') return 'missing';
          var c = [].slice.call(document.querySelectorAll('.channel')).filter(function (x) {
            return (x.textContent || '').indexOf('问点点') >= 0;
          })[0];
          if (!c) return 'missing';
          c.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /** 当前路径与 URL 里的 conversationId，JSON 字符串，见 [AskTabState.parse]。 */
    val ASK_TAB_STATE = """
        (function () {
          try {
          return JSON.stringify({ path: location.pathname, conversationId: new URLSearchParams(location.search).get('conversationId') || '' });
          } catch (e) { return JSON.stringify({ path: '', conversationId: '' }); }
        })()
    """.trimIndent()

    /** 浏览器后退：`/ai_chat_tab` 经 SPA 回到 AI 结果页，不重新请求笔记、总结（真机验证）。 */
    val HISTORY_BACK = "(function () { history.back(); return 'ok'; })()"

    /** 点击根评论下的「展开 N 条回复 / 展开更多回复」。返回 clicked / missing。 */
    fun expandReplies(rootCommentId: String): String = """
        (function () {
          var c = document.querySelector('#comment-$rootCommentId');
          var p = c && c.closest('.parent-comment');
          var b = p && p.querySelector('.show-more');
          if (!b) return 'missing';
          b.click();
          return 'clicked';
        })()
    """.trimIndent()

    /** 读出用户主页首屏（资料 + 服务端渲染的笔记），转成 [com.rednoteseek.core.network.api.ProfileSnapshot] 的 JSON；未就绪返回空串。 */
    val PROFILE_SNAPSHOT = """
        (function () {
          function u(v) { return v && v._rawValue !== undefined ? v._rawValue : (v && v._value !== undefined ? v._value : v); }
          try {
            if (window.__rnsStale) return '';
            var s = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.user;
            if (!s) return '';
            var page = u(s.userPageData);
            if (!page || !page.basicInfo || !page.basicInfo.nickname) return '';
            var b = page.basicInfo;
            var counts = {};
            (page.interactions || []).forEach(function (i) { counts[i.type] = i.count; });
            var arr = u(s.notes);
            var list = (arr && u(arr[0])) || [];
            var q = u(s.noteQueries);
            var q0 = q && q[0];
            function note(n) {
              var c = n.noteCard || {};
              var cov = c.cover || {};
              var us = c.user || {};
              var it = c.interactInfo || {};
              return {
                note_id: c.noteId || n.id || '', display_title: c.displayTitle || '', type: c.type || 'normal',
                xsec_token: n.xsecToken || c.xsecToken || '', time: c.time || n.time || 0,
                cover: { url_default: cov.urlDefault || '', url: cov.url || '' },
                user: { user_id: us.userId || '', nickname: us.nickname || us.nickName || '', avatar: us.avatar || '' },
                interact_info: { liked_count: String(it.likedCount || '0') }
              };
            }
            return JSON.stringify({
              user: {
                user_id: location.pathname.split('/')[3] || '', nickname: b.nickname || '', avatar: b.imageb || b.images || '',
                desc: b.desc || '', red_id: b.redId || '',
                follows: String(counts.follows || '0'), fans: String(counts.fans || '0'), interaction: String(counts.interaction || '0')
              },
              notes: list.map(note), cursor: (q0 && q0.cursor) || '', has_more: !!(q0 && q0.hasMore)
            });
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /**
     * 自己主页「收藏 › 专辑」服务端渲染的专辑列表（`__INITIAL_STATE__.board.userBoardList`），
     * 转成 [com.rednoteseek.core.network.api.BoardList] 的 JSON；不在专辑子标签或未就绪返回空串。
     */
    val BOARD_LIST = """
        (function () {
          function u(v) { return v && v._rawValue !== undefined ? v._rawValue : (v && v._value !== undefined ? v._value : v); }
          try {
            if (window.__rnsStale) return '';
            if (location.search.indexOf('subTab=board') < 0) return '';
            var s = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.board;
            var list = s && u(s.userBoardList);
            if (!list || !list.map) return '';
            return JSON.stringify({ boards: list.map(function (x) {
              var b = u(x) || {};
              return { id: b.id || '', name: b.name || '', total: Number(b.total) || 0, images: b.images || [], privacy: Number(b.privacy) || 0 };
            }) });
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /**
     * 专辑页首屏（`__INITIAL_STATE__.board.boardFeedsMap[专辑 ID]`，字段驼峰），
     * 转成 [com.rednoteseek.core.network.api.PostedResult] 的 JSON；未就绪返回空串。
     */
    val BOARD_SNAPSHOT = """
        (function () {
          function u(v) { return v && v._rawValue !== undefined ? v._rawValue : (v && v._value !== undefined ? v._value : v); }
          try {
            if (window.__rnsStale) return '';
            var id = location.pathname.split('/')[2] || '';
            var s = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.board;
            var map = s && u(s.boardFeedsMap);
            var feed = map && u(map[id]);
            if (!feed || !feed.notes) return '';
            function note(n) {
              var cov = n.cover || {};
              var us = n.user || {};
              var it = n.interactInfo || {};
              var info = (cov.infoList || []).map(function (i) { return { image_scene: i.imageScene || '', url: i.url || '' }; });
              return {
                note_id: n.noteId || '', display_title: n.displayTitle || '', type: n.type || 'normal',
                xsec_token: n.xsecToken || '', time: n.time || 0,
                cover: { url_default: cov.urlDefault || '', url: cov.url || '', info_list: info },
                user: { user_id: us.userId || '', nickname: us.nickname || us.nickName || '', avatar: us.avatar || '' },
                interact_info: { liked_count: String(it.likedCount || '0') }
              };
            }
            return JSON.stringify({ notes: u(feed.notes).map(note), cursor: feed.cursor || '', has_more: !!feed.hasMore });
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /** 专辑页的专辑信息（`__INITIAL_STATE__.board.boardDetails`）：`{id, name, desc, privacy}` 的 JSON；未就绪返回空串。 */
    val BOARD_DETAILS = """
        (function () {
          function u(v) { return v && v._rawValue !== undefined ? v._rawValue : (v && v._value !== undefined ? v._value : v); }
          try {
            if (window.__rnsStale) return '';
            var s = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.board;
            var d = s && u(s.boardDetails);
            if (!d || !d.id) return '';
            return JSON.stringify({ id: d.id, name: d.name || '', desc: d.desc || '', privacy: Number(d.privacy) || 0 });
          } catch (e) { return ''; }
        })()
    """.trimIndent()

    /** 自己主页上点「收藏」标签。返回 missing（尚未渲染）/ active（已选中）/ clicked。 */
    val COLLECT_TAB = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          var t = [].slice.call(document.querySelectorAll('.reds-tab-item')).filter(function (x) { return (x.textContent || '').trim() === '收藏'; })[0];
          if (!t) return 'missing';
          if ((t.className || '').indexOf('active') >= 0) return 'active';
          t.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 「收藏」标签下的「笔记・N」子标签（收藏的笔记）。停在「收藏 › 专辑」时点它，网页走 SPA 内导航、
     * 只发 v2/note/collect/page（真机：0.5s 到第一页，整页加载 2.4–3.0s）。
     * 顶层标签（笔记 / 收藏 / 点赞 / 浏览记录）带 sub-tab-list class，子标签不带。返回 active / clicked / missing。
     */
    val COLLECT_NOTES_SUBTAB = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          var t = [].slice.call(document.querySelectorAll('.reds-tab-item:not(.sub-tab-list)')).filter(function (x) { return /^笔记/.test((x.textContent || '').trim()); })[0];
          if (!t) return 'missing';
          if ((t.className || '').indexOf('active') >= 0) return 'active';
          t.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 用户主页资料区的关注按钮：已是目标状态返回 done，否则点一下返回 clicked（网页直接调关注 / 取消关注接口，
     * 没有确认框）；按钮未渲染返回 missing。已关注时按钮带 `followed` class（文案「已关注」/「互相关注」）。
     */
    fun followButton(follow: Boolean): String = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          var b = document.querySelector('.user-info .follow-button');
          if (!b) return 'missing';
          if (b.classList.contains('followed') === $follow) return 'done';
          b.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 自己主页上点「关注」数字，打开关注列表弹窗（`.xhs-relation-panel`）；弹窗会请求 v1/user/followings/extra。
     * 返回 missing（尚未渲染）/ open（弹窗已打开）/ clicked。
     */
    val FOLLOWING_LIST = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          if (document.querySelector('.xhs-relation-panel')) return 'open';
          var t = [].slice.call(document.querySelectorAll('.user-interactions .clickable')).filter(function (x) { return /关注$/.test((x.textContent || '').trim()); })[0];
          if (!t) return 'missing';
          t.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /** 关掉关注列表弹窗（读完未看数后；下次在同一页面上还要再点「关注」打开）。返回 closed / none。 */
    val CLOSE_RELATION_PANEL = """
        (function () {
          try {
          var c = document.querySelector('.xhs-relation-panel .xhs-panel-close');
          if (!c) return 'none';
          c.click();
          return 'closed';
          } catch (e) { return 'none'; }
        })()
    """.trimIndent()

    /** 把任意文本变成安全的 JS 字符串字面量（JSON 字符串即合法 JS，另转义 U+2028 / U+2029）。 */
    fun jsString(text: String): String =
        Json.encodeToString(String.serializer(), text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /**
     * 打开专辑表单：[create] 时在「收藏 › 专辑」点「创建专辑」，否则在专辑页点「编辑专辑」（只有主人可见）。
     * 返回 open（表单已打开）/ clicked / missing。
     */
    fun openBoardForm(create: Boolean): String = """
        (function () {
          try {
          if (window.__rnsStale) return 'missing';
          if (document.querySelector('.reds-modal-open .modal input.input-content')) return 'open';
          var b = document.querySelector('${if (create) ".create-board" else ".board-info .btn-wrapper"}');
          if (!b) return 'missing';
          b.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 填写已打开的专辑表单并点「完成」：名称、简介按输入事件写入（网页用 v-model），
     * 「公开专辑」开关（`.dot-container`，打开时带 `turn-on`）与目标不一致就点一下。返回 submitted / missing。
     */
    fun submitBoardForm(form: BoardForm): String = """
        (function () {
          try {
          var m = document.querySelector('.reds-modal-open .modal');
          if (!m) return 'missing';
          var input = m.querySelector('input.input-content');
          var area = m.querySelector('textarea.textarea-content');
          var sw = m.querySelector('.dot-container');
          var done = m.querySelector('.btn.done');
          if (!input || !area || !sw || !done) return 'missing';
          function set(el, v) { el.focus(); el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); }
          set(input, ${jsString(form.name)});
          set(area, ${jsString(form.desc)});
          if (sw.classList.contains('turn-on') !== ${form.public}) sw.click();
          done.click();
          return 'submitted';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 删除专辑：表单里点「删除专辑」会先弹确认框（「专辑删除后，无法恢复！」），再执行一次点确认框里的「删除」。
     * 返回 asked（已弹确认框）/ confirmed / missing。
     */
    val DELETE_BOARD = """
        (function () {
          try {
          var ok = [].slice.call(document.querySelectorAll('.reds-alert-footer .foot-btn')).filter(function (b) {
            return b.offsetWidth > 0 && (b.textContent || '').trim() === '删除';
          })[0];
          if (ok) { ok.click(); return 'confirmed'; }
          var d = document.querySelector('.reds-modal-open .modal .delete-btn');
          if (!d) return 'missing';
          d.click();
          return 'asked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 笔记弹层里的收藏按钮（`#note-page-collect-board-guide`，图标 `#collected` 表示已收藏）：
     * 已是目标状态返回 done，否则点一下返回 clicked；没有弹层返回 missing。
     */
    fun collectButton(collect: Boolean): String = """
        (function () {
          try {
          var b = document.querySelector('.note-detail-mask #note-page-collect-board-guide');
          if (!b) return 'missing';
          var u = b.querySelector('use');
          var h = u ? (u.getAttribute('href') || u.getAttribute('xlink:href') || '') : '';
          if (/#collected${'$'}/.test(h) === $collect) return 'done';
          b.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 收藏成功后网页底部只显示几秒「收藏成功 | 加入专辑」：点「加入专辑」打开专辑面板（`.board-list-container`）。
     * 返回 open（面板已打开）/ clicked / missing。
     */
    val JOIN_BOARD = """
        (function () {
          try {
          var panel = document.querySelector('.board-list-container .panel-wrapper');
          if (panel && panel.offsetWidth > 0) return 'open';
          var a = [].slice.call(document.querySelectorAll('.message-content .right-area')).filter(function (x) {
            return (x.textContent || '').indexOf('加入专辑') >= 0;
          })[0];
          if (!a) return 'missing';
          a.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /** 专辑面板滚到底，触发下一页专辑（面板每页 15 个）。 */
    val SCROLL_BOARD_PANEL = """
        (function () {
          try {
          var c = document.querySelector('.board-list-container .panel-wrapper .content');
          if (!c) return 'missing';
          c.scrollTop = c.scrollHeight;
          return 'ok';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /**
     * 在专辑面板里点第 [index] 个专辑（与面板请求到的专辑同序），并核对名称防止点错。
     * 返回 clicked / loading（还没加载到该位置，已滚动面板）/ mismatch / missing。
     */
    fun pickBoard(index: Int, name: String): String = """
        (function () {
          try {
          var c = document.querySelector('.board-list-container .panel-wrapper');
          if (!c) return 'missing';
          var items = [].slice.call(c.querySelectorAll('.board-item')).filter(function (x) {
            return x.className.indexOf('board-item-loading') < 0;
          });
          if (items.length <= $index) {
            var box = c.querySelector('.content');
            if (box) box.scrollTop = box.scrollHeight;
            return 'loading';
          }
          var it = items[$index];
          var n = it.querySelector('.name-text');
          if (!n || n.textContent.trim() !== ${jsString(name)}) return 'mismatch';
          it.click();
          return 'clicked';
          } catch (e) { return 'missing'; }
        })()
    """.trimIndent()

    /** 关闭专辑面板（面板头部右侧的关闭图标）。 */
    val CLOSE_BOARD_PANEL = """
        (function () {
          try {
          var w = document.querySelectorAll('.board-list-container .panel-wrapper .header .icon-wrapper');
          var last = w[w.length - 1];
          if (!last) return 'none';
          (last.querySelector('.icon') || last).click();
          return 'closed';
          } catch (e) { return 'none'; }
        })()
    """.trimIndent()

    /**
     * 加载新地址前在旧页面上打标记：新页面加载完成前，旧文档仍会响应脚本，
     * 带标记的旧页面一律视为未就绪（真机：曾把上一个主页的资料当成新主页读出）。
     */
    val MARK_STALE = "(function () { window.__rnsStale = true; return 'ok'; })()"

    /** 当前文档是否是新文档：带 [MARK_STALE] 标记的旧文档返回 stale，新文档返回 fresh。 */
    val PAGE_FRESH = "(function(){return window.__rnsStale ? 'stale' : 'fresh';})()"

    /** 当前页面位置：路径、「收藏」标签是否选中、是否是已标记过期的旧页面，JSON 字符串。 */
    val PAGE_LOCATION = """
        (function () {
          try {
          var active = [].slice.call(document.querySelectorAll('.reds-tab-item.active')).map(function (x) { return (x.textContent || '').trim(); });
          return JSON.stringify({ path: location.pathname, collect: active.indexOf('收藏') >= 0, stale: !!window.__rnsStale, sub: new URLSearchParams(location.search).get('subTab') || '' });
          } catch (e) { return JSON.stringify({ path: '', collect: false, stale: false, sub: '' }); }
        })()
    """.trimIndent()

    /** 笔记弹层里作者链接的绝对地址（网页自己生成，含 xsec_token）；没有返回空串。 */
    val AUTHOR_LINK = """
        (function () {
          try {
          var a = document.querySelector('.note-detail-mask a[href*="/user/profile/"]');
          return a ? a.href : '';
          } catch (e) { return ''; }
        })()
    """.trimIndent()
}
