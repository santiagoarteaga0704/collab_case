// Ajustes que la app del teléfono le agrega al editor web cuando lo abre dentro de la app.
// No cambia nada de la web desplegada: solo corre dentro del WebView.
(function () {
  if (window.__collabMovil) return;
  window.__collabMovil = true;

  // 1. Pantalla de celular: barra de acciones en una sola fila que se desliza, y el panel
  //    «Asistente de modelado» como una hoja a pantalla completa que se abre con un botón.
  const style = document.createElement('style');
  style.textContent = `
    .diagram-editor-layout { grid-template-columns: 1fr !important; grid-template-rows: 100% !important; height: 100% !important; overflow: hidden !important; }
    html, body, #root { height: 100%; overflow: hidden; }
    .diagram-canvas { height: 100% !important; }
    .editor-topbar { flex-wrap: wrap; row-gap: 6px; }
    .editor-topbar .editor-action-group { flex-wrap: nowrap !important; overflow-x: auto; max-width: 100%; scrollbar-width: none; -webkit-overflow-scrolling: touch; }
    .editor-topbar .editor-action-group::-webkit-scrollbar { display: none; }
    .editor-topbar .editor-action-group > button { white-space: nowrap; flex: none; }
    .ai-panel { display: none !important; }
    body.movil-asistente .ai-panel { display: block !important; position: fixed !important; inset: 0 !important; z-index: 60 !important; height: 100% !important; width: 100% !important; border: 0 !important; }
    .movil-asistente-abrir { display: inline-flex; align-items: center; gap: 6px; }
    .movil-asistente-cerrar { margin-left: auto; flex: none; }
  `;
  document.head.appendChild(style);

  const sparkles = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/></svg>';

  function abrir() { document.body.classList.add('movil-asistente'); }
  function cerrar() { document.body.classList.remove('movil-asistente'); }
  window.__collabMovilCerrarAsistente = function () {
    if (!document.body.classList.contains('movil-asistente')) return false;
    cerrar();
    return true;
  };

  // React vuelve a dibujar el editor; si el botón desaparece, se vuelve a poner.
  function ponerBotones() {
    const group = document.querySelector('.editor-action-group');
    if (group && !group.querySelector('.movil-asistente-abrir')) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'movil-asistente-abrir';
      button.innerHTML = sparkles + ' Asistente';
      button.addEventListener('click', abrir);
      group.insertBefore(button, group.children[1] || null);
    }
    const header = document.querySelector('.ai-panel-header');
    if (header && !header.querySelector('.movil-asistente-cerrar')) {
      const close = document.createElement('button');
      close.type = 'button';
      close.className = 'icon-button movil-asistente-cerrar';
      close.setAttribute('aria-label', 'Cerrar el asistente');
      close.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>';
      close.addEventListener('click', cerrar);
      header.appendChild(close);
    }
  }
  ponerBotones();
  new MutationObserver(ponerBotones).observe(document.body, { childList: true, subtree: true });

  // 2. Descargas (exportar, generar código): la web usa enlaces blob: que el WebView no sabe
  //    guardar. Se mandan por trozos a la app, que arma el archivo y ofrece guardarlo.
  if (window.CollabDescarga) {
    const revoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = (url) => setTimeout(() => revoke(url), 120000);
    const click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (this.download && this.href && this.href.startsWith('blob:')) {
        const name = this.download;
        fetch(this.href).then((r) => r.blob()).then(async (blob) => {
          CollabDescarga.postMessage(JSON.stringify({ type: 'start', name, size: blob.size }));
          const step = 384 * 1024;
          for (let i = 0; i < blob.size; i += step) {
            const buffer = new Uint8Array(await blob.slice(i, i + step).arrayBuffer());
            let binary = '';
            for (let j = 0; j < buffer.length; j += 8192) binary += String.fromCharCode.apply(null, buffer.subarray(j, j + 8192));
            CollabDescarga.postMessage(JSON.stringify({ type: 'chunk', data: btoa(binary) }));
          }
          CollabDescarga.postMessage(JSON.stringify({ type: 'end' }));
        }).catch(() => CollabDescarga.postMessage(JSON.stringify({ type: 'error' })));
        return;
      }
      return click.call(this);
    };
  }
})();
