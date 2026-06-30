
import ReactDOM from "react-dom/client";
import { BrowserRouter, HashRouter } from "react-router-dom";

import App from "./App.tsx";
import { Provider } from "./provider.tsx";
import "@/styles/globals.css";

// iOS app 内是 file:// 加载，必须用 HashRouter（BrowserRouter 依赖 URL 路径，file:// 下匹配不到路由会白屏）；web 部署仍用 BrowserRouter
const Router = (import.meta as any).env?.VITE_IOS_BUILD ? HashRouter : BrowserRouter;

ReactDOM.createRoot(document.getElementById("root")!).render(
  <Router>
    <Provider>
      <App />
    </Provider>
  </Router>
);

