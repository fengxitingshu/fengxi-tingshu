/* 前端 ↔ 原生桥：TS.call('方法名', 参数...) → Android[方法名](...) */
window.TS = (function () {
    function call(fn) {
        try {
            if (!window.Android || typeof Android[fn] !== 'function') return null;
            return Android[fn].apply(Android, Array.prototype.slice.call(arguments, 1));
        } catch (e) { return null; }
    }
    function $(id) { return document.getElementById(id); }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }
    function toast(s) { call('toast', String(s)); }
    function copy(s) { call('copyText', String(s)); }
    function imgFail(el) {
        if (!el || el.dataset.failed) return;
        el.dataset.failed = '1';
        el.style.visibility = 'hidden';
    }
    return { call: call, $: $, esc: esc, toast: toast, copy: copy, imgFail: imgFail };
})();
