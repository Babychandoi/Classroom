// D-35: "focus" screens hide the app chrome (sidebar + top bar) so the task fills the window: creating a class, taking an
// exam, doing a homework assignment and the Question Builder. They carry their own way back.
const FOCUS_ROUTES: RegExp[] = [
  /^\/classes\/new\/?$/,
  /^\/classes\/[^/]+\/exams\/[^/]+\/attempt\/?$/,
  /^\/classes\/[^/]+\/homework\/[^/]+\/attempt\/?$/,
  /^\/studio\/classes\/[^/]+\/assessments\/[^/]+\/edit\/?$/,
];

export const isFocusRoute = (pathname: string): boolean => FOCUS_ROUTES.some((re) => re.test(pathname));

/** The home area: discovery + personal pages, where the app sidebar and the phone tab bar are shown. */
const HOME_AREA: RegExp[] = [/^\/classes\/?$/, /^\/me\//, /^\/privacy\/?$/, /^\/join\//];
/** A class page (/classes/:slug/...) - not /classes/new. */
const CLASS_AREA = /^\/classes\/(?!new\/?$)[^/]+(\/|$)/;

export const isHomeArea = (pathname: string): boolean => HOME_AREA.some((re) => re.test(pathname));
export const isClassArea = (pathname: string): boolean => CLASS_AREA.test(pathname);

/** Where the app sidebar renders: the home and class areas, never Studio / admin / login / focus screens. */
export const showsAppSidebar = (pathname: string): boolean =>
  !isFocusRoute(pathname) && (isHomeArea(pathname) || isClassArea(pathname));
