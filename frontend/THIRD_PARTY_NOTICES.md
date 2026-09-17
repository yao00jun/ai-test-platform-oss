# MeterSphere source adaptations

The route-selected/collapsible navigation in `src/components/layout/AppSidebar.vue` and the conversation placement, feedback reuse, and scroll behavior in `src/components/ai/ConversationMessages.vue` / `AiDrawer.vue` are adapted from:

- `frontend/src/components/business/ms-menu/index.vue`
- `frontend/src/components/business/ms-menu/use-menu-tree.ts`
- `frontend/src/components/business/ms-ai-drawer/index.vue`
- `frontend/src/components/business/ms-ai-drawer/components/conversation.vue`
- `frontend/src/components/business/ms-ai-drawer/components/conversationList.vue`

Adaptations replace MeterSphere's organization/permission stores, Axios cancellation, i18n, Element conversation widgets, and Tailwind/Less utilities with this project's Vue Router, Arco components, project-scoped API requests, native EventSource, and CSS tokens. The original inspected files have no individual license header; the following upstream repository notice is retained verbatim.

## Upstream LICENSE

This project is distributed under the GNU General Public License, version 3 (GPLv3),
with the following additional terms and conditions:

1. Logos and Copyright  
   You may not remove, alter, or hide any logos, trademarks, or copyright
   notices displayed in the web interface or within the source code.

2. Contributor Terms  
   By contributing to this project, you agree that:  
   a. The project maintainer may update or revise this open-source license
      to make it more permissive or more restrictive as needed.  
   b. Your contributions may be used for commercial purposes, including
      but not limited to the project’s cloud and business operations.

All other terms of the GNU General Public License, version 3 (GPLv3) remain in effect.
Full text: https://www.gnu.org/licenses/gpl-3.0.html

Copyright (c) 2026-present FIT2CLOUD.
