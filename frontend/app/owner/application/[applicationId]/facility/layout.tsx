import { ApplicationWorkspace } from '@/features/owner-application/application-page'
export default async function Layout({params,children}:{params:Promise<{applicationId:string}>;children:React.ReactNode}){const {applicationId}=await params;return <ApplicationWorkspace id={applicationId}>{children}</ApplicationWorkspace>}
