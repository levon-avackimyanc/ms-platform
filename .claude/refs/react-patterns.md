# React Code Standards

<!-- section:core -->

## 1. Functional Components Only

Functional components only. Class components are prohibited.

```tsx
// BAD: Class component — outdated approach
class UserCard extends React.Component<UserCardProps> {
  render() {
    return <div>{this.props.name}</div>;
  }
}

// GOOD: Functional component with explicit typing
interface UserCardProps {
  /** Username to display. */
  name: string;
  /** Email for mailto link. */
  email: string;
  /** Callback on card click. */
  onClick?: (userId: string) => void;
}

function UserCard({ name, email, onClick }: UserCardProps) {
  return (
    <div className="user-card" onClick={() => onClick?.(email)}>
      <h3>{name}</h3>
      <p>{email}</p>
    </div>
  );
}
```

**Rules:**
- Always `function` declaration (not `const Component = () => {}` for top-level components)
- Props interface declared separately, ABOVE the component
- `export` on the component itself or at the bottom of the file, but consistently throughout the project

## 2. Custom Hooks Extraction

Logic goes into custom hooks. Component — render only.

```tsx
// BAD: Logic scattered across the component
function UserProfile({ userId }: { userId: string }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    setLoading(true);
    fetch(`/api/users/${userId}`)
      .then((res) => res.json())
      .then((data) => {
        if (!ignore) {
          setUser(data);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!ignore) {
          setError(err.message);
          setLoading(false);
        }
      });
    return () => { ignore = true; };
  }, [userId]);

  if (loading) return <Spinner />;
  if (error) return <ErrorMessage message={error} />;
  if (!user) return null;

  return <div>{user.name}</div>;
}

// GOOD: Hook separate, component separate
function useUser(userId: string) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    setLoading(true);
    fetch(`/api/users/${userId}`)
      .then((res) => res.json())
      .then((data) => {
        if (!ignore) {
          setUser(data);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!ignore) {
          setError(err.message);
          setLoading(false);
        }
      });
    return () => { ignore = true; };
  }, [userId]);

  return { user, loading, error };
}

function UserProfile({ userId }: { userId: string }) {
  const { user, loading, error } = useUser(userId);

  if (loading) return <Spinner />;
  if (error) return <ErrorMessage message={error} />;
  if (!user) return null;

  return <div>{user.name}</div>;
}
```

**Rule:** If a component has more than one `useState` + `useEffect` — extract into a hook.

## 3. Props Typing with TypeScript Interfaces

All props are typed via `interface`. No `any`, `object`, or inline types.

```tsx
// BAD: Inline types, any, no documentation
function OrderList({ orders, onSelect }: { orders: any[]; onSelect: any }) {
  return <ul>{orders.map((o) => <li key={o.id}>{o.name}</li>)}</ul>;
}

// BAD: React.FC — hides children, interferes with generics
const OrderList: React.FC<{ orders: Order[] }> = ({ orders }) => {
  return <ul>{orders.map((o) => <li key={o.id}>{o.name}</li>)}</ul>;
};

// GOOD: Explicit interface, JSDoc comments
interface OrderListProps {
  /** List of orders to display. */
  orders: Order[];
  /** Callback on order selection. */
  onSelect: (orderId: string) => void;
  /** CSS class for the container (optional). */
  className?: string;
}

function OrderList({ orders, onSelect, className }: OrderListProps) {
  return (
    <ul className={className}>
      {orders.map((order) => (
        <li key={order.id} onClick={() => onSelect(order.id)}>
          {order.name} - {order.total}
        </li>
      ))}
    </ul>
  );
}
```

**Rules:**
- Do not use `React.FC` — interferes with generics, outdated practice
- Pass children explicitly: `children: React.ReactNode`
- Type event handlers: `onClick: (id: string) => void`, not `Function`

## 4. useState vs useReducer

`useState` for simple values. `useReducer` for related state.

```tsx
// BAD: Multiple related useState — easy to desynchronize
function RegistrationForm() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);

  async function handleSubmit() {
    setIsSubmitting(true);
    setErrors({});
    setIsSuccess(false);
    // Easy to forget to reset one of the states...
  }
}

// GOOD: useReducer for related form states
interface FormState {
  name: string;
  email: string;
  password: string;
  errors: Record<string, string>;
  status: "idle" | "submitting" | "success" | "error";
}

type FormAction =
  | { type: "SET_FIELD"; field: keyof FormState; value: string }
  | { type: "SUBMIT" }
  | { type: "SUCCESS" }
  | { type: "ERROR"; errors: Record<string, string> }
  | { type: "RESET" };

const initialState: FormState = {
  name: "",
  email: "",
  password: "",
  errors: {},
  status: "idle",
};

function formReducer(state: FormState, action: FormAction): FormState {
  switch (action.type) {
    case "SET_FIELD":
      return { ...state, [action.field]: action.value, errors: {} };
    case "SUBMIT":
      return { ...state, status: "submitting", errors: {} };
    case "SUCCESS":
      return { ...initialState, status: "success" };
    case "ERROR":
      return { ...state, status: "error", errors: action.errors };
    case "RESET":
      return initialState;
  }
}

function RegistrationForm() {
  const [state, dispatch] = useReducer(formReducer, initialState);
  // All state transitions are atomic and predictable
}
```

**When to use which:**
- `useState`: boolean flags, strings, individual numbers
- `useReducer`: forms, wizards, carts, any related state

## 5. useEffect Cleanup and Dependencies

Always cleanup. Dependencies — explicit and minimal.

```tsx
// BAD: No cleanup — memory leak, race condition
useEffect(() => {
  fetch(`/api/users/${userId}`)
    .then((res) => res.json())
    .then((data) => setUser(data));
}, [userId]);

// BAD: Object in dependencies — infinite loop
useEffect(() => {
  fetchData(filters);
}, [filters]); // filters = { page: 1, search: '' } — new object on every render!

// GOOD: Cleanup to cancel the request and prevent race condition
useEffect(() => {
  let ignore = false;
  const controller = new AbortController();

  async function fetchUser() {
    try {
      const response = await fetch(`/api/users/${userId}`, {
        signal: controller.signal,
      });
      const data = await response.json();
      if (!ignore) {
        setUser(data);
      }
    } catch (error) {
      if (!ignore && error instanceof Error && error.name !== "AbortError") {
        setError(error.message);
      }
    }
  }

  fetchUser();

  return () => {
    ignore = true;
    controller.abort();
  };
}, [userId]);

// GOOD: Primitive dependencies instead of objects
useEffect(() => {
  fetchData({ page, search });
}, [page, search]); // Primitives — stable dependencies
```

**Rules:**
- Every `useEffect` MUST have cleanup (or an explicit comment explaining why it's not needed)
- Dependencies: only primitives, stable refs, or memoized values
- Empty array `[]` = run once on mount

## 6. useMemo/useCallback — Only When Needed

DO NOT wrap everything. Memoization is needed in specific cases only.

```tsx
// BAD: Pointless memoization — overhead without benefit
function UserList({ users }: { users: User[] }) {
  const sortedUsers = useMemo(() => users.sort(byName), [users]);
  const handleClick = useCallback(() => {
    console.log("clicked");
  }, []);

  return <div onClick={handleClick}>{sortedUsers.map(renderUser)}</div>;
}

// GOOD: useMemo only for heavy computations
function DataGrid({ rows, filters }: DataGridProps) {
  // Heavy filtering + sorting of thousands of rows — memoization is justified
  const processedRows = useMemo(
    () => rows.filter(matchesFilters(filters)).sort(bySortKey),
    [rows, filters]
  );

  return <Table rows={processedRows} />;
}

// GOOD: useCallback when passing to a memoized child component
function ParentComponent() {
  const [count, setCount] = useState(0);

  // Without useCallback, ExpensiveChild will re-render on every count change
  const handleSubmit = useCallback((data: FormData) => {
    api.submit(data);
  }, []);

  return (
    <>
      <button onClick={() => setCount((c) => c + 1)}>{count}</button>
      <ExpensiveChild onSubmit={handleSubmit} />
    </>
  );
}

const ExpensiveChild = memo(function ExpensiveChild({
  onSubmit,
}: {
  onSubmit: (data: FormData) => void;
}) {
  // Heavy render — memo + useCallback on props are justified
  return <HeavyForm onSubmit={onSubmit} />;
});
```

**When to memoize:**
- `useMemo`: filtering/sorting large arrays, expensive computations
- `useCallback`: callback passed to a `memo()` component, or as a `useEffect` dependency
- **NOT needed:** simple computations, handlers directly on JSX elements

## 7. Component Composition over Prop Drilling

Composition over prop drilling through 3+ levels.

```tsx
// BAD: Prop drilling — theme passed through 3 components
function App() {
  const [theme, setTheme] = useState<Theme>("light");
  return <Layout theme={theme} setTheme={setTheme} />;
}
function Layout({ theme, setTheme }: LayoutProps) {
  return <Header theme={theme} setTheme={setTheme} />;
}
function Header({ theme, setTheme }: HeaderProps) {
  return <ThemeToggle theme={theme} setTheme={setTheme} />;
}

// GOOD: Context for global state (theme, auth, locale)
interface ThemeContextValue {
  theme: Theme;
  setTheme: (theme: Theme) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return context;
}

function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<Theme>("light");
  const value = useMemo(() => ({ theme, setTheme }), [theme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

// Component gets data from context, no prop drilling
function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  return (
    <button onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
      {theme === "light" ? "Dark" : "Light"} mode
    </button>
  );
}

// GOOD: Composition pattern — passing children instead of prop drilling
function Layout({ children }: { children: React.ReactNode }) {
  return (
    <div className="layout">
      <Sidebar />
      <main>{children}</main>
    </div>
  );
}

function App() {
  return (
    <Layout>
      <Dashboard />
    </Layout>
  );
}
```

**Rules:**
- Prop drilling through 2+ intermediate components = refactor
- Context for: theme, auth, locale, feature flags
- `children` pattern for layout components

## 8. Error Boundaries with Fallback UI

Every major UI section is wrapped in an Error Boundary.

```tsx
// BAD: One error crashes the entire application
function App() {
  return (
    <div>
      <Header />
      <Dashboard />  {/* Error here will break the entire App */}
      <Footer />
    </div>
  );
}

// GOOD: Error boundary isolates errors, class — the only acceptable case
interface ErrorBoundaryProps {
  /** Fallback UI on error. */
  fallback: React.ReactNode;
  /** Nested components. */
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("ErrorBoundary caught:", error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback;
    }
    return this.props.children;
  }
}

// Usage: each section is isolated
function App() {
  return (
    <div>
      <Header />
      <ErrorBoundary fallback={<p>Dashboard failed to load</p>}>
        <Dashboard />
      </ErrorBoundary>
      <ErrorBoundary fallback={<p>Sidebar failed to load</p>}>
        <Sidebar />
      </ErrorBoundary>
      <Footer />
    </div>
  );
}
```

**Rule:** Error Boundary — the only case where a class component is acceptable (React does not yet have a hook equivalent for `getDerivedStateFromError`).

## 9. Suspense for Async Operations

`Suspense` + `React.lazy` for code splitting. `Suspense` + data fetching via framework.

```tsx
// BAD: Manual loading state for each component
function App() {
  const [DashboardModule, setDashboard] = useState<React.ComponentType | null>(null);

  useEffect(() => {
    import("./Dashboard").then((mod) => setDashboard(() => mod.default));
  }, []);

  if (!DashboardModule) return <Spinner />;
  return <DashboardModule />;
}

// GOOD: React.lazy + Suspense for code splitting
const Dashboard = lazy(() => import("./Dashboard"));
const Settings = lazy(() => import("./Settings"));
const Analytics = lazy(() => import("./Analytics"));

function App() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="/analytics" element={<Analytics />} />
      </Routes>
    </Suspense>
  );
}

// GOOD: Nested Suspense for granular loading
function DashboardPage() {
  return (
    <div className="dashboard">
      <h1>Dashboard</h1>
      <Suspense fallback={<ChartSkeleton />}>
        <RevenueChart />
      </Suspense>
      <Suspense fallback={<TableSkeleton />}>
        <OrdersTable />
      </Suspense>
    </div>
  );
}
```

**Rules:**
- `React.lazy()` for all route-level components
- `Suspense` with a meaningful fallback (skeleton, not just a spinner)
- Nested `Suspense` for parallel section loading

## 10. Key Prop and List Rendering

Keys must be stable and unique. No `index` for dynamic lists.

```tsx
// BAD: Index as key — bugs on sorting, deletion, insertion
function TodoList({ todos }: { todos: Todo[] }) {
  return (
    <ul>
      {todos.map((todo, index) => (
        <li key={index}>{todo.text}</li>  // When deleting an element, React will confuse states
      ))}
    </ul>
  );
}

// BAD: Unstable key — remount on every render
function TodoList({ todos }: { todos: Todo[] }) {
  return (
    <ul>
      {todos.map((todo) => (
        <li key={Math.random()}>{todo.text}</li>  // New key = new DOM node every time
      ))}
    </ul>
  );
}

// GOOD: Stable unique ID from data
function TodoList({ todos }: { todos: Todo[] }) {
  if (todos.length === 0) {
    return <EmptyState message="No todos yet" />;
  }

  return (
    <ul>
      {todos.map((todo) => (
        <li key={todo.id}>
          <TodoItem todo={todo} />
        </li>
      ))}
    </ul>
  );
}

// OK: Index is acceptable ONLY for static lists (menus, navigation)
const NAV_ITEMS = ["Home", "About", "Contact"] as const;

function NavMenu() {
  return (
    <nav>
      {NAV_ITEMS.map((item, index) => (
        <a key={index} href={`/${item.toLowerCase()}`}>{item}</a>
      ))}
    </nav>
  );
}
```

**Rules:**
- `key` = unique ID from data (`todo.id`, `user.uuid`)
- `index` as key is acceptable ONLY if the list is static and is not sorted/filtered
- Always handle empty list — render EmptyState

<!-- /section:core -->

---

<!-- section:nextjs -->

## 11. Server vs Client Components

All components are server components by default. `'use client'` only when interactivity is needed.

```tsx
// BAD: 'use client' on every component — the point of SSR is lost
'use client';  // Not needed! No interactivity

import { db } from '@/lib/db';

export default function UserList() {
  const [users, setUsers] = useState<User[]>([]);

  useEffect(() => {
    fetch('/api/users').then(r => r.json()).then(setUsers);
  }, []);

  return <ul>{users.map(u => <li key={u.id}>{u.name}</li>)}</ul>;
}

// GOOD: Server Component — direct data access, no useState/useEffect
import { db } from '@/lib/db';

export default async function UserList() {
  // Direct DB query — no API routes, no useEffect
  const users = await db.user.findMany({ orderBy: { name: 'asc' } });

  return (
    <ul>
      {users.map((user) => (
        <li key={user.id}>{user.name}</li>
      ))}
    </ul>
  );
}

// GOOD: 'use client' only for interactive component
'use client';

import { useState } from 'react';

interface SearchInputProps {
  /** Callback on search query change. */
  onSearch: (query: string) => void;
}

export function SearchInput({ onSearch }: SearchInputProps) {
  const [query, setQuery] = useState('');

  function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    setQuery(e.target.value);
    onSearch(e.target.value);
  }

  return <input value={query} onChange={handleChange} placeholder="Search..." />;
}
```

**When `'use client'`:**
- `useState`, `useReducer`, `useEffect` and other hooks
- Event handlers (`onClick`, `onChange`)
- Browser APIs (`localStorage`, `window`)
- Third-party libraries without server support

**When Server Component (by default):**
- Data fetching (fetch, DB queries)
- Access to server resources (file system, env)
- Rendering static content

## 12. App Router File Conventions

Each file in `app/` has a specific purpose. Do not mix them up.

```
app/
  layout.tsx      — Shared layout (wraps children), persists across navigations
  page.tsx        — UI for this route segment
  loading.tsx     — Suspense fallback (shown while page is loading)
  error.tsx       — Error boundary for route segment (MUST be 'use client')
  not-found.tsx   — UI for 404 (invoked via notFound())
  template.tsx    — Like layout but re-mounts on navigation (rarely needed)
```

```tsx
// app/layout.tsx — Root layout, REQUIRED
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'My App',
  description: 'Application description',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Header />
        <main>{children}</main>
        <Footer />
      </body>
    </html>
  );
}

// app/dashboard/page.tsx — Server Component by default
export default async function DashboardPage() {
  const stats = await fetchDashboardStats();
  return <DashboardView stats={stats} />;
}

// app/dashboard/loading.tsx — Shown while page is loading
export default function DashboardLoading() {
  return <DashboardSkeleton />;
}

// app/dashboard/error.tsx — MUST be 'use client'
'use client';

interface ErrorPageProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function DashboardError({ error, reset }: ErrorPageProps) {
  return (
    <div>
      <h2>Something went wrong</h2>
      <p>{error.message}</p>
      <button onClick={reset}>Try again</button>
    </div>
  );
}
```

## 13. Server Actions for Mutations

Server Actions for data mutations. Not for reads.

```tsx
// BAD: API route + fetch for simple record creation
// app/api/posts/route.ts
export async function POST(request: Request) {
  const data = await request.json();
  const post = await db.post.create({ data });
  return Response.json(post);
}

// app/posts/page.tsx
'use client';
function CreatePost() {
  async function handleSubmit(data: FormData) {
    await fetch('/api/posts', { method: 'POST', body: JSON.stringify(data) });
  }
}

// GOOD: Server Action — direct call to a server function
// app/posts/actions.ts
'use server';

import { revalidatePath } from 'next/cache';
import { redirect } from 'next/navigation';
import { db } from '@/lib/db';

export async function createPost(formData: FormData) {
  const title = formData.get('title') as string;
  const content = formData.get('content') as string;

  if (!title || !content) {
    return { error: 'Title and content are required' };
  }

  await db.post.create({
    data: { title, content },
  });

  revalidatePath('/posts');
  redirect('/posts');
}

// app/posts/new/page.tsx — Server Component with form
import { createPost } from '../actions';

export default function NewPostPage() {
  return (
    <form action={createPost}>
      <input name="title" required />
      <textarea name="content" required />
      <button type="submit">Create Post</button>
    </form>
  );
}

// GOOD: Server Action in a client component with useActionState
'use client';

import { useActionState } from 'react';
import { createPost } from '../actions';

function CreatePostForm() {
  const [state, formAction, isPending] = useActionState(createPost, null);

  return (
    <form action={formAction}>
      <input name="title" required disabled={isPending} />
      <textarea name="content" required disabled={isPending} />
      {state?.error && <p className="error">{state.error}</p>}
      <button type="submit" disabled={isPending}>
        {isPending ? 'Creating...' : 'Create Post'}
      </button>
    </form>
  );
}
```

## 14. Data Fetching in Server Components

In Server Components, fetch data directly. No useEffect!

```tsx
// BAD: useEffect in Server Component — does not work
export default async function PostsPage() {
  const [posts, setPosts] = useState([]);  // Error! Hooks cannot be used in Server Component
  useEffect(() => { fetch(...)}, []);       // Error!
}

// GOOD: Direct async/await in Server Component
import { db } from '@/lib/db';

export default async function PostsPage() {
  // Direct DB query — no API route, no useEffect
  const posts = await db.post.findMany({
    orderBy: { createdAt: 'desc' },
    take: 20,
  });

  return (
    <div>
      <h1>Posts</h1>
      {posts.map((post) => (
        <PostCard key={post.id} post={post} />
      ))}
    </div>
  );
}

// GOOD: Fetch with cache control
export default async function StatsPage() {
  // Static data — cached forever (by default)
  const config = await fetch('https://api.example.com/config', {
    cache: 'force-cache',
  }).then((r) => r.json());

  // Dynamic data — not cached
  const stats = await fetch('https://api.example.com/stats', {
    cache: 'no-store',
  }).then((r) => r.json());

  // Data with revalidation every 60 seconds
  const posts = await fetch('https://api.example.com/posts', {
    next: { revalidate: 60 },
  }).then((r) => r.json());

  return <Dashboard config={config} stats={stats} posts={posts} />;
}
```

## 15. Metadata API for SEO

Metadata via `export const metadata` or `generateMetadata`.

```tsx
// BAD: <Head> from next/head — this is Pages Router, not App Router
import Head from 'next/head';

export default function Page() {
  return (
    <>
      <Head><title>My Page</title></Head>
      <div>Content</div>
    </>
  );
}

// GOOD: Static metadata
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Dashboard | My App',
  description: 'Application dashboard with analytics and metrics',
  openGraph: {
    title: 'Dashboard',
    description: 'View your analytics',
    type: 'website',
  },
};

export default function DashboardPage() {
  return <Dashboard />;
}

// GOOD: Dynamic metadata (depends on params)
interface PageProps {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { slug } = await params;
  const post = await db.post.findUnique({ where: { slug } });

  if (!post) {
    return { title: 'Post Not Found' };
  }

  return {
    title: `${post.title} | Blog`,
    description: post.excerpt,
    openGraph: {
      title: post.title,
      description: post.excerpt,
      images: post.coverImage ? [{ url: post.coverImage }] : [],
    },
  };
}

export default async function PostPage({ params }: PageProps) {
  const { slug } = await params;
  const post = await db.post.findUnique({ where: { slug } });
  if (!post) notFound();
  return <PostView post={post} />;
}
```

## 16. Route Handlers (API Routes)

Route handlers for external APIs and webhooks. For internal mutations — Server Actions.

```tsx
// app/api/webhooks/stripe/route.ts — Webhook endpoint
import { NextRequest, NextResponse } from 'next/server';
import { headers } from 'next/headers';

export async function POST(request: NextRequest) {
  const body = await request.text();
  const headersList = await headers();
  const signature = headersList.get('stripe-signature');

  if (!signature) {
    return NextResponse.json({ error: 'Missing signature' }, { status: 400 });
  }

  try {
    const event = verifyStripeWebhook(body, signature);
    await handleStripeEvent(event);
    return NextResponse.json({ received: true });
  } catch (error) {
    return NextResponse.json({ error: 'Invalid signature' }, { status: 400 });
  }
}

// app/api/users/[id]/route.ts — REST endpoint for external clients
import { NextRequest, NextResponse } from 'next/server';

interface RouteParams {
  params: Promise<{ id: string }>;
}

export async function GET(_request: NextRequest, { params }: RouteParams) {
  const { id } = await params;
  const user = await db.user.findUnique({ where: { id } });

  if (!user) {
    return NextResponse.json({ error: 'User not found' }, { status: 404 });
  }

  return NextResponse.json(user);
}
```

## 17. Middleware Patterns

Middleware for auth, redirects, i18n. Runs BEFORE rendering.

```tsx
// middleware.ts — at the project root (next to app/)
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // Skip static files and API
  if (pathname.startsWith('/_next') || pathname.startsWith('/api')) {
    return NextResponse.next();
  }

  // Auth check
  const token = request.cookies.get('auth-token')?.value;

  if (!token && pathname.startsWith('/dashboard')) {
    const loginUrl = new URL('/login', request.url);
    loginUrl.searchParams.set('redirect', pathname);
    return NextResponse.redirect(loginUrl);
  }

  // i18n: Redirect to default locale
  const locale = request.cookies.get('locale')?.value ?? 'ru';
  const response = NextResponse.next();
  response.headers.set('x-locale', locale);

  return response;
}

// matcher — specifies which paths to run middleware on
export const config = {
  matcher: [
    // All paths except static files
    '/((?!_next/static|_next/image|favicon.ico).*)',
  ],
};
```

## 18. Caching and Revalidation

`revalidatePath` and `revalidateTag` for cache invalidation after mutations.

```tsx
// BAD: No revalidation — data becomes stale
'use server';

export async function updatePost(id: string, data: PostData) {
  await db.post.update({ where: { id }, data });
  // The page shows stale data!
}

// GOOD: revalidatePath — invalidates cache for a specific path
'use server';

import { revalidatePath } from 'next/cache';

export async function updatePost(id: string, data: PostData) {
  await db.post.update({ where: { id }, data });

  revalidatePath('/posts');           // Invalidates the list page
  revalidatePath(`/posts/${id}`);     // Invalidates the post page
}

// GOOD: revalidateTag — invalidates by tag (more flexible)
import { revalidateTag } from 'next/cache';

// When fetching data — assign a tag
async function getPosts() {
  const res = await fetch('https://api.example.com/posts', {
    next: { tags: ['posts'] },
  });
  return res.json();
}

async function getPost(id: string) {
  const res = await fetch(`https://api.example.com/posts/${id}`, {
    next: { tags: ['posts', `post-${id}`] },
  });
  return res.json();
}

// On mutation — invalidate by tag
export async function updatePost(id: string, data: PostData) {
  await db.post.update({ where: { id }, data });
  revalidateTag(`post-${id}`);  // Only the specific post
}

export async function deletePost(id: string) {
  await db.post.delete({ where: { id } });
  revalidateTag('posts');  // The entire post list
}
```

## 19. Dynamic Routes and generateStaticParams

Dynamic segments `[slug]` and pre-rendering via `generateStaticParams`.

```tsx
// app/posts/[slug]/page.tsx
import { notFound } from 'next/navigation';
import { db } from '@/lib/db';

interface PostPageProps {
  params: Promise<{ slug: string }>;
}

// Static generation for known slugs
export async function generateStaticParams() {
  const posts = await db.post.findMany({ select: { slug: true } });
  return posts.map((post) => ({ slug: post.slug }));
}

export default async function PostPage({ params }: PostPageProps) {
  const { slug } = await params;
  const post = await db.post.findUnique({ where: { slug } });

  if (!post) {
    notFound(); // Will return 404 via app/posts/[slug]/not-found.tsx
  }

  return (
    <article>
      <h1>{post.title}</h1>
      <div dangerouslySetInnerHTML={{ __html: post.contentHtml }} />
    </article>
  );
}

// app/posts/[slug]/not-found.tsx
export default function PostNotFound() {
  return (
    <div>
      <h2>Post not found</h2>
      <p>The post you are looking for does not exist.</p>
    </div>
  );
}
```

## 20. Parallel Routes and Intercepting Routes

Parallel routes for complex layouts. Intercepting routes for modals.

```
app/
  @analytics/       — Parallel slot (rendered simultaneously with page)
    page.tsx
    loading.tsx
  @sidebar/          — Second parallel slot
    page.tsx
  layout.tsx         — Receives slots as props
  page.tsx
```

```tsx
// app/layout.tsx — Parallel routes as props
interface DashboardLayoutProps {
  children: React.ReactNode;
  analytics: React.ReactNode;  // @analytics slot
  sidebar: React.ReactNode;    // @sidebar slot
}

export default function DashboardLayout({
  children,
  analytics,
  sidebar,
}: DashboardLayoutProps) {
  return (
    <div className="dashboard-layout">
      <aside>{sidebar}</aside>
      <main>{children}</main>
      <section>{analytics}</section>
    </div>
  );
}

// Intercepting routes for modals:
// app/posts/(..)posts/[id]/page.tsx — intercepts /posts/[id] from feed
// Shows the post in a modal when navigating from the list,
// but shows the full page on direct URL access
```

<!-- /section:nextjs -->

---

<!-- section:vite -->

## 21. React Router v7 Setup

`createBrowserRouter` + `RouterProvider`. Object-based route configuration.

```tsx
// BAD: Old JSX-based routing
function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/about" element={<About />} />
      </Routes>
    </BrowserRouter>
  );
}

// GOOD: createBrowserRouter with object-based configuration
import { createBrowserRouter, RouterProvider } from 'react-router';

const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    errorElement: <RootError />,
    children: [
      {
        index: true,
        element: <HomePage />,
        loader: homeLoader,
      },
      {
        path: 'posts',
        element: <PostsLayout />,
        children: [
          {
            index: true,
            element: <PostsList />,
            loader: postsLoader,
          },
          {
            path: ':postId',
            element: <PostDetail />,
            loader: postDetailLoader,
            action: postAction,
          },
        ],
      },
    ],
  },
]);

function App() {
  return <RouterProvider router={router} />;
}
```

## 22. Route Loaders and Actions

Loaders for GET data, Actions for mutations. Instead of useEffect for data fetching.

```tsx
// BAD: useEffect for loading data in a component
function PostsList() {
  const [posts, setPosts] = useState<Post[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/posts').then(r => r.json()).then(setPosts).finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;
  return <ul>{posts.map(p => <li key={p.id}>{p.title}</li>)}</ul>;
}

// GOOD: Loader loads data BEFORE component render
import { useLoaderData, type LoaderFunctionArgs } from 'react-router';

// Loader — runs before render, data is available immediately
export async function postsLoader({ request }: LoaderFunctionArgs) {
  const url = new URL(request.url);
  const page = url.searchParams.get('page') ?? '1';

  const response = await fetch(`/api/posts?page=${page}`, {
    signal: request.signal,  // Cancel on navigation
  });

  if (!response.ok) {
    throw new Response('Failed to load posts', { status: response.status });
  }

  return response.json();
}

// Action — handles POST/PUT/DELETE
export async function createPostAction({ request }: LoaderFunctionArgs) {
  const formData = await request.formData();
  const title = formData.get('title') as string;
  const content = formData.get('content') as string;

  const response = await fetch('/api/posts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content }),
  });

  if (!response.ok) {
    return { error: 'Failed to create post' };
  }

  return { success: true };
}

// Component — pure render, data already loaded
function PostsList() {
  const posts = useLoaderData() as Post[];

  return (
    <ul>
      {posts.map((post) => (
        <li key={post.id}>{post.title}</li>
      ))}
    </ul>
  );
}
```

## 23. Lazy Loading with React.lazy + Suspense

Lazy loading for route-level components. Not small UI elements.

```tsx
// BAD: All components in the bundle — huge initial load
import { HomePage } from './pages/HomePage';
import { DashboardPage } from './pages/DashboardPage';
import { SettingsPage } from './pages/SettingsPage';
import { AnalyticsPage } from './pages/AnalyticsPage';

// GOOD: lazy() for route-level components
const HomePage = lazy(() => import('./pages/HomePage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage'));

// GOOD: React Router v7 built-in lazy
const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      {
        path: 'dashboard',
        lazy: () => import('./pages/DashboardPage'),
        // Module exports: Component, loader, action, errorElement
      },
      {
        path: 'settings',
        lazy: () => import('./pages/SettingsPage'),
      },
      {
        path: 'analytics',
        lazy: {
          // Granular lazy — load loader and Component separately
          loader: async () => (await import('./pages/analytics.loader')).loader,
          Component: async () => (await import('./pages/AnalyticsPage')).default,
        },
      },
    ],
  },
]);

// GOOD: Suspense with a meaningful fallback
function App() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <RouterProvider router={router} />
    </Suspense>
  );
}
```

**Rules:**
- `lazy()` only for pages and large modules, not for buttons and icons
- React Router v7 `lazy` is preferred over `React.lazy` for routes
- Fallback = page skeleton, not an empty spinner

## 24. Environment Variables (VITE_ prefix)

Client-side variables MUST have the `VITE_` prefix. Typed via `env.d.ts`.

```tsx
// BAD: No VITE_ prefix — variable will not be included in the client bundle
// .env
DATABASE_URL=postgres://...
API_KEY=secret-key

// console.log(import.meta.env.API_KEY) → undefined!

// GOOD: VITE_ prefix for client-side, no prefix for server-side
// .env
VITE_API_URL=https://api.example.com
VITE_APP_TITLE=My App
DATABASE_URL=postgres://...     # Server-side only (build scripts)

// GOOD: Typing via env.d.ts
// src/env.d.ts
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string;
  readonly VITE_APP_TITLE: string;
  readonly VITE_FEATURE_FLAGS: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// Usage — typed, autocomplete works
const API_URL = import.meta.env.VITE_API_URL;
```

## 25. Proxy Configuration for API Calls

Dev proxy to avoid CORS issues. Relative paths in code.

```tsx
// BAD: Hardcoded URL — different for dev and prod, CORS issues
async function fetchPosts() {
  const response = await fetch('http://localhost:8080/api/posts');
  return response.json();
}

// GOOD: Relative paths + Vite proxy
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});

// In code — always relative paths
async function fetchPosts(): Promise<Post[]> {
  const response = await fetch('/api/posts');  // Proxy will redirect to :8080
  if (!response.ok) {
    throw new Error(`Failed to fetch posts: ${response.status}`);
  }
  return response.json();
}
```

## 26. Code Splitting Strategies

Code splitting by routes and heavy dependencies.

```tsx
// BAD: Single entry point — everything in one bundle
import { Chart } from 'chart.js';    // 200KB!
import { Editor } from 'monaco-editor'; // 2MB!

// GOOD: Dynamic import for heavy dependencies
const ChartComponent = lazy(() =>
  import('./components/Chart').then((mod) => ({ default: mod.Chart }))
);

// GOOD: Manual chunks in vite.config.ts
// vite.config.ts
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // Vendor libraries in a separate chunk (cached independently)
          'vendor-react': ['react', 'react-dom'],
          'vendor-router': ['react-router'],
          'vendor-charts': ['chart.js', 'recharts'],
          'vendor-ui': ['@radix-ui/react-dialog', '@radix-ui/react-dropdown-menu'],
        },
      },
    },
  },
});

// GOOD: Functional splitting — a chunk for each feature module
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('react')) return 'vendor-react';
            if (id.includes('chart')) return 'vendor-charts';
            return 'vendor'; // All other npm packages
          }
        },
      },
    },
  },
});
```

## 27. Path Aliases Configuration

Aliases instead of relative paths `../../../`. Configuration in vite.config.ts + tsconfig.json.

```tsx
// BAD: Relative imports — fragile, unreadable
import { Button } from '../../../components/ui/Button';
import { useAuth } from '../../../../hooks/useAuth';
import { formatDate } from '../../../utils/format';

// GOOD: Aliases via @/
import { Button } from '@/components/ui/Button';
import { useAuth } from '@/hooks/useAuth';
import { formatDate } from '@/utils/format';
```

```ts
// vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});

// tsconfig.json (for TypeScript autocomplete)
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    }
  }
}
```

## 28. CSS Modules and Tailwind Setup

CSS Modules for style isolation. Tailwind for a utility-first approach. Do not mix.

```tsx
// BAD: Global CSS classes — name conflicts
import './UserCard.css';  // .card { } — may conflict with another .card

function UserCard() {
  return <div className="card">...</div>;
}

// GOOD option A: CSS Modules — isolation by default
// UserCard.module.css
// .card { padding: 1rem; border: 1px solid #e5e7eb; border-radius: 8px; }
// .title { font-weight: 600; }

import styles from './UserCard.module.css';

function UserCard({ name }: { name: string }) {
  return (
    <div className={styles.card}>
      <h3 className={styles.title}>{name}</h3>
    </div>
  );
}

// GOOD option B: Tailwind CSS — utility classes
function UserCard({ name }: { name: string }) {
  return (
    <div className="p-4 border border-gray-200 rounded-lg hover:shadow-md transition-shadow">
      <h3 className="font-semibold text-lg">{name}</h3>
    </div>
  );
}

// GOOD: clsx/cn for conditional classes (with Tailwind)
import { clsx } from 'clsx';

interface ButtonProps {
  variant: 'primary' | 'secondary' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  children: React.ReactNode;
}

function Button({ variant, size = 'md', disabled, children }: ButtonProps) {
  return (
    <button
      className={clsx(
        'rounded font-medium transition-colors',
        {
          'bg-blue-600 text-white hover:bg-blue-700': variant === 'primary',
          'bg-gray-200 text-gray-800 hover:bg-gray-300': variant === 'secondary',
          'bg-red-600 text-white hover:bg-red-700': variant === 'danger',
        },
        {
          'px-2 py-1 text-sm': size === 'sm',
          'px-4 py-2 text-base': size === 'md',
          'px-6 py-3 text-lg': size === 'lg',
        },
        disabled && 'opacity-50 cursor-not-allowed'
      )}
      disabled={disabled}
    >
      {children}
    </button>
  );
}
```

## 29. Build Optimization

Bundle analysis, tree-shaking, asset optimization.

```ts
// vite.config.ts — Production optimization
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Minimal target for fewer polyfills
    target: 'es2020',
    // Size warning
    chunkSizeWarningLimit: 500,
    // Source maps for production debugging (optional)
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom'],
          'vendor-router': ['react-router'],
        },
      },
    },
  },
  // Dependency optimization for dev server
  optimizeDeps: {
    include: ['react', 'react-dom', 'react-router'],
  },
});
```

```bash
# Bundle analysis — required before production release
npx vite-bundle-visualizer

# Check size
npx vite build --report
```

**Rules:**
- manualChunks for vendors — cached separately from app code
- `optimizeDeps.include` for fast dev server startup
- Check bundle size before every PR that adds a new dependency

## 30. HMR and Fast Refresh

Vite HMR works automatically. Do not break it with incorrect exports.

```tsx
// BAD: Mixing components and non-components in one file breaks Fast Refresh
// utils-and-components.tsx
export const API_URL = '/api';  // Not a component

export function UserCard() {    // Component
  return <div>User</div>;
}
// Vite Fast Refresh will NOT work — file contains mixed exports

// GOOD: Separate components and utilities
// constants.ts
export const API_URL = '/api';

// UserCard.tsx — component only
export function UserCard() {
  return <div>User</div>;
}

// GOOD: One component = one file
// components/
//   UserCard.tsx        — component
//   UserCard.module.css — styles (optional)
//   UserCard.test.tsx   — tests
//   index.ts            — re-export
```

**Rules:**
- One file = one component (or one hook)
- Constants, types, utilities — in separate files
- Do not mix `export default` and named exports in the same file for components

<!-- /section:vite -->

---

# Quick Checklist

Before submitting React/TypeScript code:

**Core React (all projects):**
- [ ] Functional components only (class only for ErrorBoundary)
- [ ] Props typed via interface (not inline, not any)
- [ ] `React.FC` not used — regular function declaration
- [ ] Complex logic extracted into custom hooks
- [ ] useEffect has cleanup and correct dependencies
- [ ] useMemo/useCallback only where really needed (not everywhere)
- [ ] No prop drilling through 2+ components — Context or composition used instead
- [ ] Error Boundaries wrap major UI sections
- [ ] Suspense with skeleton fallback for lazy-loaded components
- [ ] Stable keys for dynamic lists (not index, not random)

**Next.js App Router:**
- [ ] Server Components by default, `'use client'` only for interactivity
- [ ] Correct file conventions (page.tsx, layout.tsx, loading.tsx, error.tsx)
- [ ] Server Actions for mutations (not API routes for internal operations)
- [ ] Data fetching in Server Components (async/await, not useEffect)
- [ ] Metadata via `export const metadata` or `generateMetadata`
- [ ] `revalidatePath`/`revalidateTag` after mutations
- [ ] `generateStaticParams` for dynamic routes (SSG)
- [ ] error.tsx components have `'use client'`

**Vite SPA:**
- [ ] `createBrowserRouter` with object configuration (not JSX routes)
- [ ] Route loaders for data fetching (not useEffect)
- [ ] Lazy loading for route-level components
- [ ] `VITE_` prefix for client-side env variables + env.d.ts typing
- [ ] Proxy configured in vite.config.ts, code uses relative paths
- [ ] Manual chunks for vendor libraries
- [ ] Path aliases `@/` in vite.config.ts + tsconfig.json
- [ ] One file = one component (for Fast Refresh)
