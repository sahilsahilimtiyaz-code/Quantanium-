document.addEventListener('DOMContentLoaded', () => {
  const mobileMenuBtn = document.getElementById('mobileMenuBtn');
  const navLinks = document.getElementById('navLinks');

  if (mobileMenuBtn && navLinks) {
    mobileMenuBtn.addEventListener('click', () => {
      const isOpen = navLinks.classList.toggle('active');
      mobileMenuBtn.setAttribute('aria-expanded', String(isOpen));
      mobileMenuBtn.textContent = isOpen ? '✕' : '☰';
    });

    navLinks.querySelectorAll('a').forEach((link) => {
      link.addEventListener('click', () => {
        navLinks.classList.remove('active');
        mobileMenuBtn.setAttribute('aria-expanded', 'false');
        mobileMenuBtn.textContent = '☰';
      });
    });
  }

  const tabButtons = document.querySelectorAll('.gallery-tab-btn');
  const galleryCards = document.querySelectorAll('.gallery-card');

  tabButtons.forEach((btn) => {
    btn.addEventListener('click', () => {
      tabButtons.forEach((b) => {
        b.classList.remove('active');
        b.setAttribute('aria-pressed', 'false');
      });
      btn.classList.add('active');
      btn.setAttribute('aria-pressed', 'true');

      const filter = btn.getAttribute('data-filter');
      galleryCards.forEach((card) => {
        const category = card.getAttribute('data-category');
        card.style.display = filter === 'all' || category === filter ? 'flex' : 'none';
      });
    });
  });

  const lightboxModal = document.getElementById('lightboxModal');
  const lightboxImg = document.getElementById('lightboxImg');
  const lightboxCaption = document.getElementById('lightboxCaption');
  const lightboxClose = document.getElementById('lightboxClose');

  function openLightbox(src, title) {
    if (!lightboxModal || !lightboxImg || !lightboxCaption) return;
    lightboxImg.src = src;
    lightboxImg.alt = title;
    lightboxCaption.textContent = title;
    if (typeof lightboxModal.showModal === 'function') {
      if (!lightboxModal.open) lightboxModal.showModal();
    } else {
      lightboxModal.setAttribute('open', 'true');
    }
  }

  function closeLightbox() {
    if (!lightboxModal) return;
    if (typeof lightboxModal.close === 'function' && lightboxModal.open) {
      lightboxModal.close();
    } else {
      lightboxModal.removeAttribute('open');
    }
    if (lightboxImg) {
      lightboxImg.removeAttribute('src');
    }
  }

  galleryCards.forEach((card) => {
    card.addEventListener('click', () => {
      const src = card.getAttribute('data-src') || card.querySelector('img')?.getAttribute('src') || '';
      const title = card.getAttribute('data-title') || card.querySelector('.gallery-card-title')?.textContent || '';
      if (src) openLightbox(src, title);
    });
  });

  if (lightboxClose) {
    lightboxClose.addEventListener('click', closeLightbox);
  }

  if (lightboxModal) {
    lightboxModal.addEventListener('click', (e) => {
      if (e.target === lightboxModal) closeLightbox();
    });
  }

  const copyButtons = document.querySelectorAll('.copy-btn');
  copyButtons.forEach((btn) => {
    btn.addEventListener('click', async () => {
      const targetId = btn.getAttribute('data-copy-target');
      const codeEl = targetId ? document.getElementById(targetId) : null;
      if (!codeEl) return;

      const textToCopy = codeEl.innerText;
      const originalText = btn.textContent;
      try {
        await navigator.clipboard.writeText(textToCopy);
        btn.textContent = 'Copied';
        setTimeout(() => {
          btn.textContent = originalText;
        }, 1800);
      } catch {
        btn.textContent = 'Copy Ready';
        setTimeout(() => {
          btn.textContent = originalText;
        }, 1800);
      }
    });
  });
});
